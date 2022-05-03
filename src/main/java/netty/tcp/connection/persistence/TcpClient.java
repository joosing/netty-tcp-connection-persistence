package netty.tcp.connection.persistence;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
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

    public ChannelFuture connectOnce(String ip, int port) {
        return bootstrap.connect(ip, port);
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
            final ChannelFuture future = bootstrap.connect(ip, port);
            try {
                future.get();
                if (future.isSuccess()) {
                    channel = future.channel();
                }
            } catch (InterruptedException e) {
                log.error("interrupted", e);
            } catch (ExecutionException e) {
                log.error("execution exception", e);
            }
            return future.isSuccess();
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
