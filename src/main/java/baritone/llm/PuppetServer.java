package baritone.llm;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;

public class PuppetServer {

    private static EventLoopGroup bossGroup;
    private static EventLoopGroup workerGroup;
    private static ChannelFuture channelFuture;

    public static void start() {
        ConfigManager config = ConfigManager.getInstance();
        if (!"puppet".equals(config.mode) && !"both".equals(config.mode)) {
            return;
        }

        int port = config.puppet_port;
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
             .channel(NioServerSocketChannel.class)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 public void initChannel(SocketChannel ch) {
                     ch.pipeline().addLast(new HttpServerCodec());
                     ch.pipeline().addLast(new HttpObjectAggregator(1048576));
                     ch.pipeline().addLast(new PuppetServerHandler());
                 }
             })
             .option(ChannelOption.SO_BACKLOG, 128)
             .childOption(ChannelOption.SO_KEEPALIVE, true);

            channelFuture = b.bind(port).sync();
            System.out.println("Baritone PuppetServer started on port " + port);

            Runtime.getRuntime().addShutdownHook(new Thread(PuppetServer::stop));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static void stop() {
        if (channelFuture != null) {
            try {
                channelFuture.channel().close().sync();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        if (workerGroup != null) workerGroup.shutdownGracefully();
        if (bossGroup != null) bossGroup.shutdownGracefully();
    }

    private static class PuppetServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
            if (req.method() != HttpMethod.POST) {
                sendResponse(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{\"error\": \"Only POST allowed\"}");
                return;
            }

            String jsonPayload = req.content().toString(CharsetUtil.UTF_8);

            // Dispatch to ActionDispatcher and return result
            ActionDispatcher.dispatch(jsonPayload).thenAccept(responseJson -> {
                sendResponse(ctx, HttpResponseStatus.OK, responseJson);
            });
        }

        private void sendResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String content) {
            ByteBuf buf = Unpooled.copiedBuffer(content, CharsetUtil.UTF_8);
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buf);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
            
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            
            ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
        }
    }
}
