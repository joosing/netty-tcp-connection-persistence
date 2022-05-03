package netty.tcp.connection.persistence;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

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
