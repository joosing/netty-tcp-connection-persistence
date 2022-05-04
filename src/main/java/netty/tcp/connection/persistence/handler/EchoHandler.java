package netty.tcp.connection.persistence.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

public class EchoHandler extends SimpleChannelInboundHandler<String> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) throws Exception {
        ctx.channel().writeAndFlush(msg);
        ctx.fireChannelRead(msg);
    }
}
