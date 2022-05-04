package netty.tcp.connection.persistence.controller;

import java.util.concurrent.ExecutionException;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public class TcpServer {
    private ServerBootstrap bootstrap;
    private EventLoopGroup listenEventLoopGroup;
    private EventLoopGroup serviceEventLoopGroup;
    private ChannelInitializer<SocketChannel> channelInitializer;

    public void init(ChannelInitializer<SocketChannel> channelInitializer)
            throws InterruptedException {
        this.channelInitializer = channelInitializer;
    }

    public boolean start(String bindIp, int bindPort) throws ExecutionException, InterruptedException {
        listenEventLoopGroup = new NioEventLoopGroup();
        serviceEventLoopGroup = new NioEventLoopGroup();
        bootstrap = new ServerBootstrap();
        bootstrap.group(listenEventLoopGroup, serviceEventLoopGroup)
                 .channel(NioServerSocketChannel.class)
                 .localAddress(bindIp, bindPort)
                 .childHandler(channelInitializer)
                 .option(ChannelOption.SO_REUSEADDR, true);

        final ChannelFuture future = bootstrap.bind();
        future.get();
        return future.isSuccess();
    }

    public void shutdown() throws InterruptedException {
        if (listenEventLoopGroup != null) {
            listenEventLoopGroup.shutdownGracefully().sync();
        }
        if (serviceEventLoopGroup != null) {
            serviceEventLoopGroup.shutdownGracefully().sync();
        }
    }
}
