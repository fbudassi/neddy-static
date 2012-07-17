package com.fbudassi.neddy;

import com.fbudassi.neddy.config.Config;
import com.fbudassi.neddy.handler.ChannelGroupHandler;
import com.fbudassi.neddy.handler.IdleKeepAliveHandler;
import com.fbudassi.neddy.handler.StaticContentHandler;
import java.util.concurrent.TimeUnit;
import org.jboss.netty.channel.ChannelHandler;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import static org.jboss.netty.channel.Channels.pipeline;
import org.jboss.netty.handler.codec.http.HttpChunkAggregator;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;
import org.jboss.netty.handler.stream.ChunkedWriteHandler;
import org.jboss.netty.handler.timeout.IdleStateHandler;
import org.jboss.netty.util.Timer;

/**
 * Builds the pipeline of handlers used to analyze the request and create the
 * response.
 *
 * @author federico
 */
public class NeddyPipelineFactory implements ChannelPipelineFactory {

    private static final int KEEPALIVE_TIMEOUT = Config.getIntValue(Config.KEY_KEEPALIVE_TIMEOUT);
    private final ChannelHandler idleStateHandler;

    public NeddyPipelineFactory(Timer timer) {
        this.idleStateHandler = new IdleStateHandler(timer, 0, 0,
                KEEPALIVE_TIMEOUT, TimeUnit.MILLISECONDS); // timer must be shared
    }

    /**
     * Configuration for the Neddy pipeline.
     *
     * @return
     * @throws Exception
     */
    @Override
    public ChannelPipeline getPipeline() throws Exception {
        ChannelPipeline pipeline = pipeline();
        pipeline.addLast("decoder", new HttpRequestDecoder());
        pipeline.addLast("aggregator", new HttpChunkAggregator(65536));
        pipeline.addLast("encoder", new HttpResponseEncoder());
        pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());
        pipeline.addLast("channelGroupHandler", new ChannelGroupHandler());
        pipeline.addLast("staticContentHandler", new StaticContentHandler());
        pipeline.addLast("idleStateHandler", this.idleStateHandler);
        pipeline.addLast("idleKeepAliveHandler", new IdleKeepAliveHandler());
        return pipeline;
    }
}
