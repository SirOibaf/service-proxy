/* Copyright 2016 predic8 GmbH, www.predic8.com

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package com.predic8.membrane.core.rules;

import com.google.common.base.Objects;


import com.predic8.membrane.core.Router;
import com.predic8.membrane.core.config.security.SSLParser;
import com.predic8.membrane.core.exchange.Exchange;
import com.predic8.membrane.core.interceptor.Interceptor;
import com.predic8.membrane.core.transport.http.Connection;
import com.predic8.membrane.core.transport.http.ConnectionManager;
import com.predic8.membrane.core.transport.http.StreamPump;
import com.predic8.membrane.core.transport.http.client.ConnectionConfiguration;
import com.predic8.membrane.core.transport.ssl.StaticSSLContext;
import com.predic8.membrane.core.transport.ssl.SSLContext;
import com.predic8.membrane.core.transport.ssl.SSLProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.Map;

public class SSLProxy implements Rule {
    private static Logger log = LoggerFactory.getLogger(SSLProxy.class.getName());

    private Target target;
    private ConnectionConfiguration connectionConfiguration = new ConnectionConfiguration();

    public static class Target {
        private int port = -1;
        private String host;

        public int getPort() {
            return port;
        }

        
        public void setPort(int port) {
            this.port = port;
        }

        public String getHost() {
            return host;
        }

        
        public void setHost(String host) {
            this.host = host;
        }
    }

    public ConnectionConfiguration getConnectionConfiguration() {
        return connectionConfiguration;
    }

    public void setConnectionConfiguration(ConnectionConfiguration connectionConfiguration) {
        this.connectionConfiguration = connectionConfiguration;
    }

    public Target getTarget() {
        return target;
    }

    
    public void setTarget(Target target) {
        this.target = target;
    }

    @Override
    public List<Interceptor> getInterceptors() {
        return null;
    }

    @Override
    public void setInterceptors(List<Interceptor> interceptors) {

    }

    @Override
    public boolean isBlockRequest() {
        return false;
    }

    @Override
    public boolean isBlockResponse() {
        return false;
    }

    int port;

    public int getPort() {
        return port;
    }

    
    public void setPort(int port) {
        this.port = port;
    }

    String host;

    public String getHost() {
        return host;
    }

    
    public void setHost(String host) {
        this.host = host;
    }

    @Override
    public RuleKey getKey() {
        return new MyRuleKey();
    }

    @Override
    public void setKey(RuleKey ruleKey) {

    }

    @Override
    public void setName(String name) {
    }

    @Override
    public String getName() {
        return "SSL " + getHost() + ":" + getPort();
    }

    @Override
    public void setBlockRequest(boolean blockStatus) {

    }

    @Override
    public void setBlockResponse(boolean blockStatus) {

    }

    @Override
    public void collectStatisticsFrom(Exchange exc) {

    }

    @Override
    public Map<Integer, StatisticCollector> getStatisticsByStatusCodes() {
        return null;
    }

    @Override
    public int getCount() {
        return 0;
    }

    ConnectionManager cm;

    @Override
    public SSLContext getSslInboundContext() {
        return new StaticSSLContext(new SSLParser(), router.getResolverMap(), router.getBaseLocation()) {

            @Override
            public Socket wrap(Socket socket, byte[] buffer, int position) throws IOException {
                int port = target.getPort();
                if (port == -1)
                    port = getPort();

                StreamPump.StreamPumpStats streamPumpStats = router.getStatistics().getStreamPumpStats();
                String protocol = "SSL";

                Connection con = cm.getConnection(target.getHost(), port, connectionConfiguration.getLocalAddr(), null, connectionConfiguration.getTimeout());

                con.out.write(buffer, 0, position);
                con.out.flush();

                String source = socket.getRemoteSocketAddress().toString();
                String dest = con.toString();
                final StreamPump a = new StreamPump(con.in, socket.getOutputStream(), streamPumpStats, protocol + " " + source + " <- " + dest, SSLProxy.this);
                final StreamPump b = new StreamPump(socket.getInputStream(), con.out, streamPumpStats, protocol + " " + source + " -> " + dest, SSLProxy.this);

                socket.setSoTimeout(0);

                String threadName = Thread.currentThread().getName();
                new Thread(a, threadName + " " + protocol + " Backward Thread").start();
                try {
                    Thread.currentThread().setName(threadName + " " + protocol + " Onward Thread");
                    b.run();
                } finally {
                    try {
                        con.close();
                    } catch (IOException e) {
                        log.debug("", e);
                    }
                }
                throw new SocketException("SSL Forwarding Connection closed.");
            }

            @Override
            public String constructHostNamePattern() {
                return getKey().getHost();
            }
        };
    }

    @Override
    public SSLProvider getSslOutboundContext() {
        return null;
    }

    Router router;

    @Override
    public void init(Router router) throws Exception {
        this.router = router;
        cm = new ConnectionManager(connectionConfiguration.getKeepAliveTimeout());
    }

    @Override
    public boolean isTargetAdjustHostHeader() {
        return false;
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public String getErrorState() {
        return null;
    }

    @Override
    public SSLProxy clone() throws CloneNotSupportedException {
        SSLProxy clone = (SSLProxy) super.clone();
        try {
            clone.init(router);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return clone;
    }

    private class MyRuleKey implements RuleKey {
        @Override
        public int getPort() {
            return port;
        }

        @Override
        public String getMethod() {
            return null;
        }

        @Override
        public String getPath() {
            return null;
        }

        @Override
        public String getHost() {
            return host;
        }

        @Override
        public boolean isMethodWildcard() {
            return false;
        }

        @Override
        public boolean isPathRegExp() {
            return false;
        }

        @Override
        public boolean isUsePathPattern() {
            return false;
        }

        @Override
        public void setUsePathPattern(boolean usePathPattern) {

        }

        @Override
        public void setPathRegExp(boolean pathRegExp) {

        }

        @Override
        public void setPath(String path) {

        }

        @Override
        public boolean matchesPath(String path) {
            return false;
        }

        @Override
        public String getIp() {
            return null;
        }

        @Override
        public void setIp(String ip) {

        }

        @Override
        public boolean matchesHostHeader(String hostHeader) {
            return false;
        }

        @Override
        public boolean matchesVersion(String version) {
            return false;
        }

        @Override
        public boolean complexMatch(String hostHeader, String method, String uri, String version, int port, String localIP) {
            return false;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof MyRuleKey))
                return false;
            MyRuleKey other = (MyRuleKey)obj;
            return Objects.equal(getHost(), other.getHost()) && getPort() == other.getPort();
        }
    }
}
