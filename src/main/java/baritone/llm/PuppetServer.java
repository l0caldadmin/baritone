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

    @SuppressWarnings("deprecation")
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

            channelFuture = b.bind(port).addListener(future -> {
                if (future.isSuccess()) {
                    System.out.println("Baritone PuppetServer started on port " + port);
                } else {
                    System.err.println("Baritone PuppetServer failed to start on port " + port + ": " + future.cause().getMessage());
                    stop();
                }
            });

            Runtime.getRuntime().addShutdownHook(new Thread(PuppetServer::stop));
        } catch (Exception e) {
            System.err.println("Failed to initialize Baritone PuppetServer groups: " + e.getMessage());
            stop();
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
            if (req.method() == HttpMethod.GET && "/gui".equals(req.uri())) {
                sendResponse(ctx, HttpResponseStatus.OK, getGuiHtml(), "text/html");
                return;
            }

            if (req.method() != HttpMethod.POST) {
                sendResponse(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{\"error\": \"Only POST allowed (or GET /gui)\"}", "application/json");
                return;
            }

            String jsonPayload = req.content().toString(CharsetUtil.UTF_8);

            // Dispatch to ActionDispatcher and return result
            ActionDispatcher.dispatch(jsonPayload).thenAccept(responseJson -> {
                sendResponse(ctx, HttpResponseStatus.OK, responseJson, "application/json");
            });
        }

        private void sendResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String content, String contentType) {
            ByteBuf buf = Unpooled.copiedBuffer(content, CharsetUtil.UTF_8);
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buf);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
            
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            
            ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
        }

        private String getGuiHtml() {
            return "<!DOCTYPE html>\n" +
                   "<html lang='en'>\n" +
                   "<head>\n" +
                   "    <meta charset='UTF-8'>\n" +
                   "    <title>Baritone Puppet Dashboard</title>\n" +
                   "    <link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">\n" +
                   "    <link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>\n" +
                   "    <link href=\"https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500&display=swap\" rel=\"stylesheet\">\n" +
                   "    <style>\n" +
                   "        :root { --bg: #09090b; --card: #18181b; --card-hover: #27272a; --accent: #8b5cf6; --accent-glow: rgba(139, 92, 246, 0.2); --text: #f4f4f5; --text-dim: #a1a1aa; --success: #10b981; --danger: #ef4444; --warning: #f59e0b; --border: #27272a; }\n" +
                   "        * { box-sizing: border-box; }\n" +
                   "        body { font-family: 'Inter', sans-serif; background: var(--bg); color: var(--text); margin: 0; display: flex; flex-direction: column; height: 100vh; overflow: hidden; }\n" +
                   "        \n" +
                   "        /* Layout */\n" +
                   "        .app-shell { display: grid; grid-template-columns: 280px 1fr 320px; grid-template-rows: 64px 1fr; height: 100vh; }\n" +
                   "        \n" +
                   "        /* Header */\n" +
                   "        header { grid-column: 1 / -1; background: var(--card); border-bottom: 1px solid var(--border); display: flex; align-items: center; justify-content: space-between; padding: 0 24px; z-index: 10; }\n" +
                   "        .brand { display: flex; align-items: center; gap: 12px; font-weight: 800; font-size: 1.25rem; letter-spacing: -0.02em; background: linear-gradient(135deg, #c084fc, #8b5cf6); -webkit-background-clip: text; -webkit-text-fill-color: transparent; }\n" +
                   "        \n" +
                   "        .vitals { display: flex; gap: 20px; align-items: center; }\n" +
                   "        .vital-stat { display: flex; flex-direction: column; gap: 4px; min-width: 100px; }\n" +
                   "        .vital-label { font-size: 10px; font-weight: 700; color: var(--text-dim); text-transform: uppercase; letter-spacing: 0.05em; }\n" +
                   "        .progress-bg { height: 6px; background: #27272a; border-radius: 3px; overflow: hidden; }\n" +
                   "        .progress-fill { height: 100%; transition: width 0.3s ease; }\n" +
                   "        .hp-fill { background: var(--danger); box-shadow: 0 0 10px rgba(239, 68, 68, 0.4); }\n" +
                   "        .food-fill { background: var(--warning); box-shadow: 0 0 10px rgba(245, 158, 11, 0.4); }\n" +
                   "        \n" +
                   "        /* Sidebars */\n" +
                   "        .sidebar { background: var(--card); border-right: 1px solid var(--border); overflow-y: auto; padding: 24px; display: flex; flex-direction: column; gap: 32px; }\n" +
                   "        .sidebar-right { border-right: none; border-left: 1px solid var(--border); }\n" +
                   "        \n" +
                   "        h2 { font-size: 0.75rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.1em; color: var(--text-dim); margin: 0 0 16px 0; display: flex; align-items: center; justify-content: space-between; }\n" +
                   "        \n" +
                   "        /* Controls */\n" +
                   "        .control-stack { display: flex; flex-direction: column; gap: 12px; }\n" +
                   "        .btn { background: var(--accent); color: white; border: none; padding: 10px 16px; border-radius: 8px; cursor: pointer; font-weight: 600; font-size: 0.875rem; transition: all 0.2s; display: flex; align-items: center; justify-content: center; gap: 8px; }\n" +
                   "        .btn:hover { filter: brightness(1.1); transform: translateY(-1px); box-shadow: 0 4px 12px var(--accent-glow); }\n" +
                   "        .btn:active { transform: translateY(0); }\n" +
                   "        .btn-secondary { background: #27272a; border: 1px solid #3f3f46; }\n" +
                   "        .btn-secondary:hover { background: #3f3f46; }\n" +
                   "        .btn-danger { background: var(--danger); }\n" +
                   "        .btn-sm { padding: 6px 10px; font-size: 0.75rem; }\n" +
                   "        \n" +
                   "        .input-group { position: relative; }\n" +
                   "        input { background: #09090b; border: 1px solid var(--border); color: white; padding: 10px 12px; border-radius: 8px; font-size: 0.875rem; width: 100%; transition: border-color 0.2s; }\n" +
                   "        input:focus { border-color: var(--accent); outline: none; box-shadow: 0 0 0 2px var(--accent-glow); }\n" +
                   "        \n" +
                   "        .setting-toggle { display: flex; align-items: center; justify-content: space-between; padding: 8px 12px; background: #09090b; border-radius: 8px; border: 1px solid var(--border); font-size: 0.875rem; }\n" +
                   "        .toggle-switch { width: 32px; height: 18px; background: #3f3f46; border-radius: 9px; position: relative; cursor: pointer; transition: 0.2s; }\n" +
                   "        .toggle-switch::after { content: ''; position: absolute; width: 14px; height: 14px; background: white; border-radius: 50%; top: 2px; left: 2px; transition: 0.2s; }\n" +
                   "        .toggle-switch.on { background: var(--success); }\n" +
                   "        .toggle-switch.on::after { left: 16px; }\n" +
                   "        \n" +
                   "        /* Waypoints */\n" +
                   "        .waypoint-list { display: flex; flex-direction: column; gap: 8px; }\n" +
                   "        .waypoint-item { background: #09090b; border: 1px solid var(--border); padding: 10px; border-radius: 8px; display: flex; justify-content: space-between; align-items: center; transition: 0.2s; }\n" +
                   "        .waypoint-item:hover { border-color: var(--accent); background: #111115; }\n" +
                   "        .wp-info { display: flex; flex-direction: column; gap: 2px; }\n" +
                   "        .wp-name { font-size: 0.875rem; font-weight: 600; }\n" +
                   "        .wp-coords { font-size: 0.75rem; color: var(--text-dim); font-family: 'JetBrains Mono', monospace; }\n" +
                   "        \n" +
                   "        /* Main View */\n" +
                   "        .main-content { padding: 24px; background: #09090b; display: flex; flex-direction: column; gap: 24px; }\n" +
                   "        #console { flex: 1; background: #050507; border-radius: 12px; border: 1px solid var(--border); padding: 20px; font-family: 'JetBrains Mono', monospace; font-size: 13px; overflow-y: auto; line-height: 1.6; position: relative; }\n" +
                   "        .log-entry { margin-bottom: 8px; padding-bottom: 8px; border-bottom: 1px solid #111115; animation: fadeIn 0.2s ease-out; }\n" +
                   "        .log-time { color: var(--text-dim); margin-right: 12px; font-weight: 400; }\n" +
                   "        .log-tag { padding: 2px 6px; border-radius: 4px; font-size: 10px; font-weight: 700; text-transform: uppercase; margin-right: 12px; }\n" +
                   "        .tag-action { background: var(--accent-glow); color: #c084fc; }\n" +
                   "        .tag-status { background: rgba(16, 185, 129, 0.1); color: var(--success); }\n" +
                   "        .tag-error { background: rgba(239, 68, 68, 0.1); color: var(--danger); }\n" +
                   "        \n" +
                   "        @keyframes fadeIn { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }\n" +
                   "        \n" +
                   "        /* Status Bar Bottom */\n" +
                   "        .status-strip { background: var(--card); border-top: 1px solid var(--border); padding: 8px 24px; display: flex; align-items: center; justify-content: space-between; font-size: 12px; color: var(--text-dim); font-weight: 500; }\n" +
                   "        .status-item { display: flex; align-items: center; gap: 8px; }\n" +
                   "        .status-dot { width: 8px; height: 8px; border-radius: 50%; background: #3f3f46; }\n" +
                   "        .status-dot.active { background: var(--success); box-shadow: 0 0 8px var(--success); }\n" +
                   "        .status-dot.warning { background: var(--warning); box-shadow: 0 0 8px var(--warning); }\n" +
                   "        \n" +
                   "        /* Inventory Grid */\n" +
                   "        .inv-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(48px, 1fr)); gap: 8px; }\n" +
                   "        .inv-item { width: 48px; height: 48px; background: #09090b; border: 1px solid var(--border); border-radius: 8px; display: flex; align-items: center; justify-content: center; position: relative; transition: 0.2s; cursor: help; }\n" +
                   "        .inv-item:hover { border-color: var(--accent); transform: scale(1.05); }\n" +
                   "        .inv-count { position: absolute; bottom: 2px; right: 4px; font-size: 10px; font-weight: 800; color: white; text-shadow: 1px 1px 0 black; }\n" +
                   "        .inv-icon { font-size: 20px; }\n" +
                   "        \n" +
                   "        /* Task Progress Bar */\n" +
                   "        .task-progress-container { flex: 1; margin: 0 24px; display: flex; flex-direction: column; gap: 4px; }\n" +
                   "        .task-progress-bar { height: 4px; background: #27272a; border-radius: 2px; overflow: hidden; }\n" +
                   "        .task-progress-fill { height: 100%; background: var(--accent); width: 0%; transition: width 0.5s ease; }\n" +
                   "    </style>\n" +
                   "</head>\n" +
                   "<body>\n" +
                   "    <div class='app-shell'>\n" +
                   "        <header>\n" +
                   "            <div class='brand'>BARITONE PUPPET</div>\n" +
                   "            <div class='vitals'>\n" +
                   "                <div class='vital-stat'>\n" +
                   "                    <div class='vital-label'>Health <span id='hp-val'>20</span></div>\n" +
                   "                    <div class='progress-bg'><div id='hp-bar' class='progress-fill hp-fill' style='width: 100%'></div></div>\n" +
                   "                </div>\n" +
                   "                <div class='vital-stat'>\n" +
                   "                    <div class='vital-label'>Food <span id='food-val'>20</span></div>\n" +
                   "                    <div class='progress-bg'><div id='food-bar' class='progress-fill food-fill' style='width: 100%'></div></div>\n" +
                   "                </div>\n" +
                   "            </div>\n" +
                   "            <div style='display: flex; gap: 24px;'>\n" +
                   "                <div class='vital-stat' style='min-width: 120px;'>\n" +
                   "                    <div class='vital-label'>Dimension</div>\n" +
                   "                    <div id='dim-text' style='font-size: 12px; font-weight: 600;'>minecraft:overworld</div>\n" +
                   "                </div>\n" +
                   "                <div class='vital-stat' style='min-width: 140px;'>\n" +
                   "                    <div class='vital-label'>Coordinates</div>\n" +
                   "                    <div id='pos-text' style='font-size: 12px; font-weight: 600; font-family: \"JetBrains Mono\", monospace;'>0, 0, 0</div>\n" +
                   "                </div>\n" +
                   "            </div>\n" +
                   "        </header>\n" +
                   "\n" +
                   "        <!-- Left Sidebar: Controls -->\n" +
                   "        <div class='sidebar'>\n" +
                   "            <section>\n" +
                   "                <h2>Navigation</h2>\n" +
                   "                <div class='control-stack'>\n" +
                   "                    <div style='display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8px;'>\n" +
                   "                        <input id='nav-x' type='number' placeholder='X'>\n" +
                   "                        <input id='nav-y' type='number' placeholder='Y'>\n" +
                   "                        <input id='nav-z' type='number' placeholder='Z'>\n" +
                   "                    </div>\n" +
                   "                    <button class='btn' onclick='doGoto()'>Go to Coordinates</button>\n" +
                   "                    <button class='btn btn-secondary' onclick=\"sendCommand('mc_explore', {})\">Explore Biome</button>\n" +
                   "                    <button class='btn btn-secondary' onclick=\"sendCommand('mc_find_village', {})\">Search for Village</button>\n" +
                   "                </div>\n" +
                   "            </section>\n" +
                   "\n" +
                   "            <section>\n" +
                   "                <h2>Schematic Builder</h2>\n" +
                   "                <div class='control-stack'>\n" +
                   "                    <div style='display: flex; gap: 8px;'>\n" +
                   "                        <select id='schematic-select' style='background: #09090b; border: 1px solid var(--border); color: white; padding: 10px 12px; border-radius: 8px; font-size: 0.875rem; flex: 1;'>\n" +
                   "                            <option value=''>Loading...</option>\n" +
                   "                        </select>\n" +
                   "                        <button class='btn btn-secondary btn-sm' onclick='refreshSchematics()'>🔄</button>\n" +
                   "                    </div>\n" +
                   "                    <button class='btn' onclick='doBuild()'>Build at Current Pos</button>\n" +
                   "                </div>\n" +
                   "            </section>\n" +
                   "\n" +
                   "            <section>\n" +
                   "                <h2>Mining & Resources</h2>\n" +
                   "                <div class='control-stack'>\n" +
                   "                    <div style='display: flex; gap: 8px;'>\n" +
                   "                        <input id='mine-id' placeholder='iron_ore' style='flex:1'>\n" +
                   "                        <input id='mine-qty' type='number' value='1' style='width: 60px;'>\n" +
                   "                    </div>\n" +
                   "                    <button class='btn' onclick=\"sendCommand('mc_mine', {block_id: document.getElementById('mine-id').value, quantity: parseInt(document.getElementById('mine-qty').value)})\">Start Mining</button>\n" +
                   "                </div>\n" +
                   "            </section>\n" +
                   "\n" +
                   "            <section>\n" +
                   "                <h2>Crafting & Recipes</h2>\n" +
                   "                <div class='control-stack'>\n" +
                   "                    <div style='display: flex; gap: 8px;'>\n" +
                   "                        <input id='craft-id' placeholder='pickaxe' style='flex:1'>\n" +
                   "                        <input id='craft-qty' type='number' value='1' style='width: 60px;'>\n" +
                   "                    </div>\n" +
                   "                    <button class='btn btn-secondary' onclick=\"sendCommand('mc_craft', {item_id: document.getElementById('craft-id').value, quantity: parseInt(document.getElementById('craft-qty').value)})\">Craft Item</button>\n" +
                   "                </div>\n" +
                   "            </section>\n" +
                   "\n" +
                   "            <section>\n" +
                   "                <h2>Settings</h2>\n" +
                   "                <div class='control-stack'>\n" +
                   "                    <div class='setting-toggle'>\n" +
                   "                        <span>Allow Break</span>\n" +
                   "                        <div id='toggle-allowBreak' class='toggle-switch' onclick=\"toggleSetting('allowBreak')\"></div>\n" +
                   "                    </div>\n" +
                   "                    <div class='setting-toggle'>\n" +
                   "                        <span>Allow Place</span>\n" +
                   "                        <div id='toggle-allowPlace' class='toggle-switch' onclick=\"toggleSetting('allowPlace')\"></div>\n" +
                   "                    </div>\n" +
                   "                    <div class='setting-toggle'>\n" +
                   "                        <span>Allow Sprint</span>\n" +
                   "                        <div id='toggle-allowSprint' class='toggle-switch' onclick=\"toggleSetting('allowSprint')\"></div>\n" +
                   "                    </div>\n" +
                   "                </div>\n" +
                   "            </section>\n" +
                   "        </div>\n" +
                   "\n" +
                   "        <!-- Main Center: Console -->\n" +
                   "        <div class='main-content'>\n" +
                   "            <div style='display: flex; justify-content: space-between; align-items: center;'>\n" +
                   "                <h2 style='margin:0'>Live Activity Log</h2>\n" +
                   "                <button class='btn btn-secondary btn-sm' onclick=\"document.getElementById('console').innerHTML=''\">Clear</button>\n" +
                   "            </div>\n" +
                   "            <div id='console'>\n" +
                   "                <div class='log-entry'>\n" +
                   "                    <span class='log-time'>00:00:00</span>\n" +
                   "                    <span class='log-tag tag-status'>System</span>\n" +
                   "                    Puppet interface initialized and waiting for data...\n" +
                   "                </div>\n" +
                   "            </div>\n" +
                   "            \n" +
                   "            <div class='status-strip'>\n" +
                   "                <div class='status-item'>\n" +
                   "                    <div id='status-dot' class='status-dot'></div>\n" +
                   "                    STATUS: <b id='status-text' style='color: white'>IDLE</b>\n" +
                   "                </div>\n" +
                   "                <div class='task-progress-container' id='task-progress-container' style='display:none'>\n" +
                   "                    <div style='display: flex; justify-content: space-between; font-size: 10px;'>\n" +
                   "                        <span id='task-name-display'>NONE</span>\n" +
                   "                        <span id='task-count-display'>0 / 0</span>\n" +
                   "                    </div>\n" +
                   "                    <div class='task-progress-bar'><div id='task-progress-fill' class='task-progress-fill'></div></div>\n" +
                   "                </div>\n" +
                   "                <div class='status-item'>\n" +
                   "                    PHASE: <b id='phase-text' style='color: var(--accent)'>IDLE</b>\n" +
                   "                </div>\n" +
                   "            </div>\n" +
                   "        </div>\n" +
                   "\n" +
                   "        <!-- Right Sidebar: Data -->\n" +
                   "        <div class='sidebar sidebar-right'>\n" +
                   "            <section>\n" +
                   "                <div style='display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;'>\n" +
                   "                    <h2 style='margin:0'>Inventory</h2>\n" +
                   "                    <button class='btn btn-secondary btn-sm' onclick=\"sendCommand('mc_inventory', {})\">Refresh</button>\n" +
                   "                </div>\n" +
                   "                <div id='inv-grid' class='inv-grid'></div>\n" +
                   "            </section>\n" +
                   "            \n" +
                   "            <section>\n" +
                   "                <h2>Waypoints</h2>\n" +
                   "                <div id='wp-list' class='waypoint-list'>\n" +
                   "                    <div style='font-size: 12px; color: var(--text-dim); text-align: center; padding: 20px;'>No waypoints found in current world.</div>\n" +
                   "                </div>\n" +
                   "            </section>\n" +
                   "            \n" +
                   "            <section style='margin-top: auto;'>\n" +
                   "                <button class='btn btn-danger' style='width: 100%; height: 50px;' onclick=\"sendCommand('mc_stop', {})\">ABORT ALL TASKS</button>\n" +
                   "            </div>\n" +
                   "        </div>\n" +
                   "    </div>\n" +
                   "\n" +
                   "    <script>\n" +
                   "        const consoleEl = document.getElementById('console');\n" +
                   "        let settingsState = { allowBreak: true, allowPlace: true, allowSprint: true };\n" +
                   "        \n" +
                   "        function log(tag, message, type='action') {\n" +
                   "            const time = new Date().toLocaleTimeString();\n" +
                   "            const entry = document.createElement('div');\n" +
                   "            entry.className = 'log-entry';\n" +
                   "            entry.innerHTML = `<span class='log-time'>${time}</span><span class='log-tag tag-${type}'>${tag}</span> ${message}`;\n" +
                   "            consoleEl.prepend(entry);\n" +
                   "        }\n" +
                   "\n" +
                   "        async function sendCommand(action, args) {\n" +
                   "            try {\n" +
                   "                const res = await fetch('/', {\n" +
                   "                    method: 'POST',\n" +
                   "                    headers: { 'Content-Type': 'application/json' },\n" +
                   "                    body: JSON.stringify({ action, args })\n" +
                   "                });\n" +
                   "                const data = await res.json();\n" +
                   "                if (data.status === 'success') {\n" +
                   "                    if (action === 'mc_inventory') updateInventory(data.inventory);\n" +
                   "                    if (action === 'mc_waypoints') updateWaypoints(data.waypoints);\n" +
                   "                    if (action !== 'mc_status') log(action, data.message || 'Command executed successfully');\n" +
                   "                } else {\n" +
                   "                    log(action, data.message || 'Error executing command', 'error');\n" +
                   "                }\n" +
                   "                return data;\n" +
                   "            } catch (e) {\n" +
                   "                log(action, e.message, 'error');\n" +
                   "            }\n" +
                   "        }\n" +
                   "\n" +
                   "        function updateInventory(items) {\n" +
                   "            const grid = document.getElementById('inv-grid');\n" +
                   "            grid.innerHTML = '';\n" +
                   "            (items || []).forEach(item => {\n" +
                   "                const el = document.createElement('div');\n" +
                   "                el.className = 'inv-item';\n" +
                   "                el.title = item.id;\n" +
                   "                el.innerHTML = `<div class='inv-icon'>📦</div><div class='inv-count'>${item.count}</div>`;\n" +
                   "                grid.appendChild(el);\n" +
                   "            });\n" +
                   "        }\n" +
                   "        \n" +
                   "        function updateWaypoints(wps) {\n" +
                   "            const list = document.getElementById('wp-list');\n" +
                   "            list.innerHTML = '';\n" +
                   "            if (!wps || wps.length === 0) {\n" +
                   "                list.innerHTML = '<div style=\"font-size: 12px; color: var(--text-dim); text-align: center; padding: 20px;\">No waypoints found.</div>';\n" +
                   "                return;\n" +
                   "            }\n" +
                   "            wps.forEach(wp => {\n" +
                   "                const el = document.createElement('div');\n" +
                   "                el.className = 'waypoint-item';\n" +
                   "                el.innerHTML = `\n" +
                   "                    <div class='wp-info'>\n" +
                   "                        <div class='wp-name'>${wp.name}</div>\n" +
                   "                        <div class='wp-coords'>${wp.x}, ${wp.y}, ${wp.z}</div>\n" +
                   "                    </div>\n" +
                   "                    <button class='btn btn-secondary btn-sm' onclick=\"sendCommand('mc_goto', {x:${wp.x}, y:${wp.y}, z:${wp.z}})\">GO</button>\n" +
                   "                `;\n" +
                   "                list.appendChild(el);\n" +
                   "            });\n" +
                   "        }\n" +
                   "\n" +
                   "        async function toggleSetting(name) {\n" +
                   "            const newValue = !settingsState[name];\n" +
                   "            const data = await sendCommand('mc_settings', { setting: name, value: newValue });\n" +
                   "            if (data.status === 'success') {\n" +
                   "                settingsState[name] = newValue;\n" +
                   "                document.getElementById('toggle-' + name).className = newValue ? 'toggle-switch on' : 'toggle-switch';\n" +
                   "            }\n" +
                   "        }\n" +
                   "\n" +
                   "        function doGoto() {\n" +
                   "            const x = parseInt(document.getElementById('nav-x').value);\n" +
                   "            const y = parseInt(document.getElementById('nav-y').value);\n" +
                   "            const z = parseInt(document.getElementById('nav-z').value);\n" +
                   "            if (!isNaN(x) && !isNaN(y) && !isNaN(z)) sendCommand('mc_goto', {x, y, z});\n" +
                   "        }\n" +
                   "\n" +
                   "        async function refreshSchematics() {\n" +
                   "            const data = await sendCommand('mc_schematic_list', {});\n" +
                   "            if (data.status === 'success') {\n" +
                   "                const sel = document.getElementById('schematic-select');\n" +
                   "                sel.innerHTML = '';\n" +
                   "                if (data.schematics.length === 0) {\n" +
                   "                    sel.innerHTML = '<option value=\"\">No schematics found</option>';\n" +
                   "                } else {\n" +
                   "                    data.schematics.forEach(s => {\n" +
                   "                        const opt = document.createElement('option');\n" +
                   "                        opt.value = s;\n" +
                   "                        opt.innerText = s;\n" +
                   "                        sel.appendChild(opt);\n" +
                   "                    });\n" +
                   "                }\n" +
                   "            }\n" +
                   "        }\n" +
                   "\n" +
                   "        function doBuild() {\n" +
                   "            const schematic = document.getElementById('schematic-select').value;\n" +
                   "            if (schematic) sendCommand('mc_build', { schematic });\n" +
                   "        }\n" +
                   "\n" +
                   "\n" +
                   "        async function poll() {\n" +
                   "            const data = await sendCommand('mc_status', {});\n" +
                   "            if (!data) return;\n" +
                   "            \n" +
                   "            // Update Vitals\n" +
                   "            document.getElementById('hp-bar').style.width = (data.hp / 20 * 100) + '%';\n" +
                   "            document.getElementById('hp-val').innerText = Math.round(data.hp);\n" +
                   "            document.getElementById('food-bar').style.width = (data.food / 20 * 100) + '%';\n" +
                   "            document.getElementById('food-val').innerText = data.food;\n" +
                   "            \n" +
                   "            // Update Location\n" +
                   "            document.getElementById('pos-text').innerText = `${Math.round(data.x)}, ${Math.round(data.y)}, ${Math.round(data.z)}`;\n" +
                   "            document.getElementById('dim-text').innerText = data.dimension;\n" +
                   "            \n" +
                   "            // Update Status Strip\n" +
                   "            const active = data.active_task !== 'none';\n" +
                   "            const paused = data.is_paused === true;\n" +
                   "            document.getElementById('status-dot').className = data.is_pathing ? 'status-dot active' : (paused ? 'status-dot warning' : 'status-dot');\n" +
                   "            document.getElementById('status-text').innerText = data.is_pathing ? 'PATHING' : (paused ? 'PAUSED' : (active ? 'ACTIVE' : 'IDLE'));\n" +
                   "            document.getElementById('status-text').style.color = data.is_pathing ? 'var(--success)' : (paused ? 'var(--warning)' : (active ? 'var(--warning)' : 'white'));\n" +
                   "            \n" +
                   "            document.getElementById('phase-text').innerText = data.task_phase;\n" +
                   "            \n" +
                   "            // Update Progress Bar\n" +
                   "            const progContainer = document.getElementById('task-progress-container');\n" +
                   "            if (active && data.quantity_target > 0) {\n" +
                   "                progContainer.style.display = 'flex';\n" +
                   "                document.getElementById('task-name-display').innerText = data.active_task.toUpperCase();\n" +
                   "                document.getElementById('task-count-display').innerText = `${data.quantity_current} / ${data.quantity_target}`;\n" +
                   "                const pct = (data.quantity_current / data.quantity_target) * 100;\n" +
                   "                document.getElementById('task-progress-fill').style.width = pct + '%';\n" +
                   "            } else {\n" +
                   "                progContainer.style.display = 'none';\n" +
                   "            }\n" +
                   "        }\n" +
                   "        \n" +
                   "        async function syncSettings() {\n" +
                   "            for (let s of ['allowBreak', 'allowPlace', 'allowSprint']) {\n" +
                   "                const data = await sendCommand('mc_settings', { setting: s });\n" +
                   "                if (data.status === 'success') {\n" +
                   "                    settingsState[s] = (data.current_value === 'true');\n" +
                   "                    document.getElementById('toggle-' + s).className = settingsState[s] ? 'toggle-switch on' : 'toggle-switch';\n" +
                   "                }\n" +
                   "            }\n" +
                   "        }\n" +
                   "\n" +
                   "        setInterval(poll, 1000);\n" +
                   "        setTimeout(() => {\n" +
                   "            syncSettings();\n" +
                   "            sendCommand('mc_inventory', {});\n" +
                   "            sendCommand('mc_waypoints', {});\n" +
                   "            refreshSchematics();\n" +
                   "        }, 500);\n" +
                   "    </script>\n" +
                   "</body>\n" +
                   "</html>";
        }
    }
}
