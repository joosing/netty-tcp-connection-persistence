package netty.tcp.connection.persistence;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;
import netty.tcp.connection.persistence.util.ExecuteUntilSuccess;

@Slf4j
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

    public Future<?> shutdown() {
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
