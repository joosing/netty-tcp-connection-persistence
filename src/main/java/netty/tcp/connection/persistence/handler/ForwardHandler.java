package netty.tcp.connection.persistence.handler;

import java.util.concurrent.BlockingQueue;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ForwardHandler extends SimpleChannelInboundHandler<String> {
    private final BlockingQueue<String> forwardQueue;
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) throws Exception {
        forwardQueue.put(msg);
        ctx.fireChannelRead(msg);
    }
}
