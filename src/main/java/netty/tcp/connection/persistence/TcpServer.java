package netty.tcp.connection.persistence;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public class TcpServer {
    private final ServerBootstrap bootstrap = new ServerBootstrap();
    private final EventLoopGroup listenEventLoopGroup = new NioEventLoopGroup();
    private final EventLoopGroup serviceEventLoopGroup = new NioEventLoopGroup();

    public void init(ChannelInitializer<SocketChannel> channelInitializer)
            throws InterruptedException {
        bootstrap.group(listenEventLoopGroup, serviceEventLoopGroup)
                 .channel(NioServerSocketChannel.class)
                 .childHandler(channelInitializer)
                 .option(ChannelOption.SO_REUSEADDR, true);
    }

    public ChannelFuture start(String bindIp, int bindPort) {
        return bootstrap.bind(bindIp, bindPort);
    }

    public void shutdown() {
        listenEventLoopGroup.shutdownGracefully();
        serviceEventLoopGroup.shutdownGracefully();
    }
}
