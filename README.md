# 왜 만들었나?

Netty 프레임워크 기반 TCP 클라이언트 파트를 구현하며 아래의 요구들을 신경쓰지 않아도 되는 서비스가 필요했습니다.
- 서버가 죽었다가 살아나면 자동으로 연결이 재수립되면 좋겠다.
- 서버가 시작되기 전이라도 연결 요청하면 언젠가 서가 시작될 때 연결이 수립되면 좋겠다.

# 기능구성
크게 구현 파트는 3부분으로 나눌 수 있습니다.
## 01. ExecuteUntilSuccess
특정 Action 이 성공할 떄 까지 재시도하는 기능을 제공합니다.
```java
public class ExecuteUntilSuccess {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final CountDownLatch cancel = new CountDownLatch(1);
    private Future<Boolean> future;
    @Getter private long intervalMillis;
    @Getter private long timeoutMillis;

    public Future<Boolean> begin(Supplier<Boolean> tryAction, long timeoutMillis, long intervalMillis) {
        this.timeoutMillis = timeoutMillis;
        this.intervalMillis = intervalMillis;
        future = executor.submit(() -> {
            long runningTime = 0;

            // 타임아웃 시간 동안 반복
            while (runningTime < timeoutMillis) {

                // 성공 시도
                if (tryAction.get()) {
                    return true;
                }

                // 취소 요청 대기 = 재시도 간격 대기
                if (cancel.await(intervalMillis, TimeUnit.MILLISECONDS)) {
                    return false;
                }

                // 시도 시간 누적
                runningTime += intervalMillis;
            }
            return false;
        });

        return future;
    }

    public void stop() {
        cancel.countDown();
        try {
            future.get(intervalMillis * 2, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("stop error", e);
        }
    }
}
```
## 02. ChannelConnectionObservable
Netty ChannelPipeline 에 위치한 ChannelHandler 로서 채널의 연결 및 연결해제 상태변경을 이벤트로 수신하고 외부 Listener 에게 연결 상태를 알립니다.
```java
public class ChannelConnectionObservable extends ChannelInboundHandlerAdapter {
    private final PropertyChangeSupport support = new PropertyChangeSupport(this);

    public ChannelConnectionObservable(PropertyChangeListener listener) {
        addListener(listener);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        support.firePropertyChange("connected", null, true);
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        support.firePropertyChange("connected", null, false);
        super.channelInactive(ctx);
    }

    public void addListener(PropertyChangeListener listener) {
        support.addPropertyChangeListener(listener);
    }
}
```

## 03. TcpClient
ExecuteUntilSuccess 모듈에게 TCP 연결을 성공할 떄 까지 요청하고 `ChannelConnectionObservable` 로부터 연결 상태를 알림받아 연결 재시도를 수행합니다.
```java
public class TcpClient implements PropertyChangeListener {
    private final Bootstrap bootstrap = new Bootstrap();
    private final ExecuteUntilSuccess executeUntilSuccess = new ExecuteUntilSuccess();
    private Channel channel;
    private boolean persistence = true;

    public void init(ChannelInitializer<NioSocketChannel> channelInitializer) {
        final EventLoopGroup eventLoopGroup = new NioEventLoopGroup();
        bootstrap.group(eventLoopGroup)
                 .channel(NioSocketChannel.class)
                 .handler(channelInitializer);
    }

    public Future<?> destroy() {
        return bootstrap.config().group().shutdownGracefully();
    }

    public boolean connectOnce(String ip, int port) throws InterruptedException, ExecutionException {
        persistence = true;
        return connectOnce0(ip, port);
    }

    /**
     * 연결을 시도하고 결과를 기다린 후 반환합니다.
     * 연결 성공 시, 채널 파이프라인 맨 앞에 연결 상태를 모니터링할 수 있는 ChannelConnectionObservable 핸들러를 추가합니다.
     * @return 연결 시도 결과
     */
    private boolean connectOnce0(String ip, int port) throws InterruptedException, ExecutionException {
        final ChannelFuture future = bootstrap.connect(ip, port);
        future.get();
        if (future.isSuccess()) {
            channel = future.channel();
            channel.pipeline().addFirst(new ChannelConnectionObservable(this));
        }
        return future.isSuccess();
    }

    /**
     * 연결에 성공할 때 까지 interval 주기로 연결 시도를 반복합니다.
     *
     * @return 연결 상태 Future
     * @param intervalMills 연결 시도가 완료되면 interval 시간 대기 후 다음 연결을 시도합니다. 연결 시도와 시도 사이는 interval 시간 보다 길 수 있습니다.
     */
    public Future<Boolean> connectUntilSuccess(String ip, int port, long timeoutMillis, long intervalMills) {
        persistence = true;

        return executeUntilSuccess.begin(() -> {
            try {
                return connectOnce0(ip, port);
            } catch (InterruptedException | ExecutionException e) {
                return false;
            }
        }, timeoutMillis, intervalMills);
    }

    public void disconnect() throws InterruptedException {
        persistence = false;
        if (channel != null) {
            channel.disconnect().sync();
        }
    }

    public ChannelFuture send(Object msg) {
        return channel.writeAndFlush(msg);
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        final Boolean connected = (Boolean) evt.getNewValue();
        assert connected != null;
        if (!connected) {
            if (persistence) {
                final InetSocketAddress address = (InetSocketAddress) channel.remoteAddress();
                connectUntilSuccess(address.getHostName(), address.getPort(),
                                    executeUntilSuccess.getTimeoutMillis(), executeUntilSuccess.getIntervalMillis());
            }
        }
    }
}
```

# 테스트
## 01. 서버 시작 전, 연결 시도
서버가 시작되기 전에 지속 연결을 요청합니다. 이후 서버가 시작되면 즉시 연결 수립됨을 확인합니다.
```java
    @Test
    void tryPersistenceConnection_beforeServerStart() throws Exception {
        // Given : 서버 시작 전, 지속 연결을 시도하는 클라이언트
        final Future<Boolean> connectionFuture = client.connectUntilSuccess("127.0.0.1", 12345, Integer.MAX_VALUE, 100);

        // When : 2초 뒤 서버 시작
        Assertions.assertThatExceptionOfType(TimeoutException.class).isThrownBy(() -> connectionFuture.get(2000, TimeUnit.MILLISECONDS));
        server.start("0.0.0.0", 12345);

        // Then : 서버 시작 즉시 연결 수립
        Assertions.assertThat(connectionFuture.get()).isTrue();
        client.send("command");
        Assertions.assertThat(responseQueue.poll(1000, TimeUnit.MILLISECONDS)).isEqualTo("Hello");
    }
```

## 02. 연결 상태에서 서버 재기동
서버-클라이언트 연결이 수립된 이후에 서버를 내립니다. 일정 시간 대기 후 서버를 재시작하면 즉시 연결이 재수립됨을 확인합니다.
```java
    @Test
    void serverRestart_inPersistenceConnection() throws Exception {
        // Given : 서버-클라이언트 연결 상태
        server.start("0.0.0.0", 12345);
        final Future<Boolean> connectionFuture = client.connectUntilSuccess("127.0.0.1", 12345, Integer.MAX_VALUE, 100);
        Assertions.assertThatNoException().isThrownBy(() -> {
            final Boolean result = connectionFuture.get(1000, TimeUnit.MILLISECONDS);
            Assertions.assertThat(result).isTrue();
        });

        // When : 서버 내린 후, 2초 후 재기동
        server.shutdown();
        client.send("command");
        Assertions.assertThat(responseQueue.poll(2000, TimeUnit.MILLISECONDS)).isNull();
        server.start("0.0.0.0", 12345);

        // Then : 서버 재기동 후 즉시 자동 재연결
        Thread.sleep(1000);
        client.send("command");
        Assertions.assertThat(responseQueue.poll(1000, TimeUnit.MILLISECONDS)).isEqualTo("Hello");
    }
```

# Github
[netty-tcp-connection-persistence](https://github.com/Jsing/netty-tcp-connection-persistence.git)
