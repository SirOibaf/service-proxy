/* Copyright 2009, 2012 predic8 GmbH, www.predic8.com

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */

package com.predic8.membrane.core;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.predic8.membrane.annot.MCAttribute;
import com.predic8.membrane.annot.MCChildElement;
import com.predic8.membrane.annot.MCElement;
import com.predic8.membrane.annot.MCMain;
import com.predic8.membrane.core.RuleManager.RuleDefinitionSource;
import com.predic8.membrane.core.config.spring.TrackingFileSystemXmlApplicationContext;
import com.predic8.membrane.core.exchangestore.ExchangeStore;
import com.predic8.membrane.core.exchangestore.LimitedMemoryExchangeStore;
import com.predic8.membrane.core.interceptor.ExchangeStoreInterceptor;
import com.predic8.membrane.core.interceptor.Interceptor;
import com.predic8.membrane.core.interceptor.administration.AdminConsoleInterceptor;
import com.predic8.membrane.core.resolver.ResolverMap;
import com.predic8.membrane.core.rules.Rule;
import com.predic8.membrane.core.transport.Transport;
import com.predic8.membrane.core.transport.http.HttpServerThreadFactory;
import com.predic8.membrane.core.transport.http.HttpTransport;
import com.predic8.membrane.core.transport.http.client.HttpClientConfiguration;
import com.predic8.membrane.core.util.DNSCache;
import com.predic8.membrane.core.util.URIFactory;

/**
 * @description <p>
 *              Membrane Service Proxy's main object.
 *              </p>
 *              <p>
 *              The router is a Spring Lifecycle object: It is automatically started and stopped according to the
 *              Lifecycle of the Spring Context containing it. In Membrane's standard setup (standalone or in a J2EE web
 *              app), Membrane itself controls the creation of the Spring Context and its Lifecycle.
 *              </p>
 *              <p>
 *              In this case, the router is <i>hot deployable</i>: It can monitor <i>proxies.xml</i>, the Spring
 *              configuration file, for changes and reinitialize the Spring Context, when a change is detected. Note
 *              that, during the Spring Context restart, the router object itself along with almost all other Membrane
 *              objects (interceptors, etc.) will be recreated.
 *              </p>
 * @topic 1. Membrane Service Proxy
 */
@MCMain(
		outputPackage="com.predic8.membrane.core.config.spring",
		outputName="router-conf.xsd",
		targetNamespace="http://membrane-soa.org/proxies/1/")
@MCElement(name="router")
public class Router {

	private static final Logger log = LoggerFactory.getLogger(Router.class.getName());

	private String baseLocation;

	protected RuleManager ruleManager = new RuleManager();
	protected ExchangeStore exchangeStore;
	protected Transport transport;
	protected ResolverMap resolverMap = new ResolverMap();
	protected DNSCache dnsCache = new DNSCache();
	protected ExecutorService backgroundInitializator =
			Executors.newSingleThreadExecutor(new HttpServerThreadFactory("Router Background Initializator"));
	protected URIFactory uriFactory = new URIFactory(false);
	protected Statistics statistics = new Statistics();
	protected String jmxRouterName;

	private boolean running;

	private int retryInitInterval = 5 * 60 * 1000; // 5 minutes
	private boolean retryInit;
	private Timer reinitializator;
	private String id;

	public Router() {
		ruleManager.setRouter(this);
	}

	public Collection<Rule> getRules() {
		return getRuleManager().getRulesBySource(RuleDefinitionSource.SPRING);
	}

	@MCChildElement(order=3)
	public void setRules(Collection<Rule> proxies) {
		for (Rule rule : proxies)
			getRuleManager().addProxy(rule, RuleDefinitionSource.SPRING);
	}

	public static Router init(String configFileName)
			throws MalformedURLException {
		log.debug("loading spring config from classpath: " + configFileName);
		return init(configFileName, Router.class.getClassLoader());
	}

	public static Router init(String resource, ClassLoader classLoader) {
		log.debug("loading spring config: " + resource);

		TrackingFileSystemXmlApplicationContext beanFactory =
				new TrackingFileSystemXmlApplicationContext(new String[] { resource }, false);
		beanFactory.setClassLoader(classLoader);
		beanFactory.refresh();
		beanFactory.start();

		return (Router) beanFactory.getBean("router");
	}

	public RuleManager getRuleManager() {
		return ruleManager;
	}

	public void setRuleManager(RuleManager ruleManager) {
		this.ruleManager = ruleManager;
		ruleManager.setRouter(this);
	}

	public ExchangeStore getExchangeStore() {
		return exchangeStore;
	}

	/**
	 * @description Spring Bean ID of an {@link ExchangeStore}. The exchange store will be used by this router's
	 *              components ({@link AdminConsoleInterceptor}, {@link ExchangeStoreInterceptor}, etc.) by default, if
	 *              no other exchange store is explicitly set to be used by them.
	 * @default create a {@link LimitedMemoryExchangeStore} limited to the size of 1 MB.
	 */
	@MCAttribute
	public void setExchangeStore(ExchangeStore exchangeStore) {
		this.exchangeStore = exchangeStore;
	}

	public Transport getTransport() {
		return transport;
	}

	@MCChildElement(order=1, allowForeign=true)
	public void setTransport(Transport transport) {
		this.transport = transport;
	}

	public HttpClientConfiguration getHttpClientConfig() {
		return resolverMap.getHTTPSchemaResolver().getHttpClientConfig();
	}

	@MCChildElement(order=0)
	public void setHttpClientConfig(HttpClientConfiguration httpClientConfig) {
		resolverMap.getHTTPSchemaResolver().setHttpClientConfig(httpClientConfig);
	}

	public DNSCache getDnsCache() {
		return dnsCache;
	}

	public ResolverMap getResolverMap() {
		return resolverMap;
	}

	/**
	 * Closes all ports (if any were opened) and waits for running exchanges to complete.
	 *
	 * When running as an embedded servlet, this has no effect.
	 */
	public void shutdown() throws IOException {
		backgroundInitializator.shutdown();
		if (transport != null)
			transport.closeAll();
	}

	/**
	 * Closes all ports (if any were opened), but does not wait for running exchanges to complete.
	 *
	 * @deprecated Simply invokes {@link #shutdown()}. "Not waiting" is not supported anymore, as open connections can
	 *             now be forcibly closed after a timeout. See
	 *             {@link HttpTransport#setForceSocketCloseOnHotDeployAfter(int)}.
	 */
	@Deprecated
	public void shutdownNoWait() throws IOException {
		shutdown();
	}

	public ExecutorService getBackgroundInitializator() {
		return backgroundInitializator;
	}

	public Rule getParentProxy(Interceptor interceptor) {
		for (Rule r : getRuleManager().getRules()) {
			for (Interceptor i : r.getInterceptors())
				if (i == interceptor)
					return r;
		}
		throw new IllegalArgumentException("No parent proxy found for the given interceptor.");
	}

	public void add(Rule rule) throws IOException {
		ruleManager.addProxyAndOpenPortIfNew(rule);
	}

	public void init() throws Exception {
		for (Rule rule : getRuleManager().getRules())
			rule.init(this);
		transport.init(this);
	}

	public void start() {
		try {
			if (exchangeStore == null)
				exchangeStore = new LimitedMemoryExchangeStore();
			if (transport == null)
				transport = new HttpTransport();

			init();
			getRuleManager().openPorts();

			if (retryInitInterval > 0)
				startAutoReinitializator();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		running = true;
		log.info(Constants.PRODUCT_NAME + " " + Constants.VERSION + " up and running!");
	}

	private void startAutoReinitializator() {
		if (getInactiveRules().isEmpty())
			return;

		reinitializator = new Timer("auto reinitializator", true);
		reinitializator.schedule(new TimerTask() {
			@Override
			public void run() {
				tryReinitialization();
			}
		}, retryInitInterval, retryInitInterval);
	}

	public void tryReinitialization() {
		boolean stillFailing = false;
		ArrayList<Rule> inactive = getInactiveRules();
		if (inactive.size() > 0) {
			log.info("Trying to activate all inactive rules.");
			for (Rule rule : inactive) {
				try {
					Rule newRule = rule.clone();
					if (!newRule.isActive()) {
						log.info("New rule is still not active.");
						stillFailing = true;
					}
					getRuleManager().replaceRule(rule, newRule);
				} catch (CloneNotSupportedException e) {
					log.error("", e);
				}
			}
		}
		if (stillFailing)
			log.info("There are still inactive rules.");
		else {
			stopAutoReinitializator();
			log.info("All rules have been initialized.");
		}
	}

	private void stopAutoReinitializator() {
		Timer reinitializator2 = reinitializator;
		if (reinitializator2 != null) {
			reinitializator2.cancel();
		}
	}

	public void stop() {
		try {
			shutdown();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		running = false;
	}

	public boolean isRunning() {
		return running;
	}

	public int getRetryInitInterval() {
		return retryInitInterval;
	}

	/**
	 * @description number of milliseconds after which reinitialization of &lt;soapProxy&gt;s should be attempted periodically
	 * @default 5 minutes
	 */
	@MCAttribute
	public void setRetryInitInterval(int retryInitInterval) {
		this.retryInitInterval = retryInitInterval;
	}

	private ArrayList<Rule> getInactiveRules() {
		ArrayList<Rule> inactive = new ArrayList<Rule>();
		for (Rule rule : getRuleManager().getRules())
			if (!rule.isActive())
				inactive.add(rule);
		return inactive;
	}

	public String getBaseLocation() {
		return baseLocation;
	}

	public void setBaseLocation(String baseLocation) {
		this.baseLocation = baseLocation;
	}

	public boolean isRetryInit() {
		return retryInit;
	}

	/**
	 * @explanation
	 * <p>Whether the router should continue startup, if initialization of a rule (proxy, serviceProxy or soapProxy) failed
	 * (for example, when a WSDL a component depends on could not be downloaded).</p>
	 * <p>If false, the router will exit with code -1 just after startup, when the initialization of a rule failed.</p>
	 * <p>If true, the router will continue startup, and all rules which could not be initialized will be <i>inactive</i> (=not
	 * {@link Rule#isActive()}).</p>
	 * <h3>Inactive rules</h3>
	 * <p>Inactive rules will simply be ignored for routing decissions for incoming requests.
	 * This means that requests for inactive rules might be routed using different routes or result in a "400 Bad Request"
	 * when no active route could be matched to the request.</p>
	 * <p>Once rules become active due to reinitialization, they are considered in future routing decissions.</p>
	 * <h3>Reinitialization</h3>
	 * <p>Inactive rules may be <i>reinitialized</i> and, if reinitialization succeeds, become active.</p>
	 * <p>By default, reinitialization is attempted at regular intervals using a timer (see {@link #setRetryInitInterval(int)}).</p>
	 * <p>Additionally, using the {@link AdminConsoleInterceptor}, an admin may trigger reinitialization of inactive rules at any time.</p>
	 * @default false
	 */
	@MCAttribute
	public void setRetryInit(boolean retryInit) {
		this.retryInit = retryInit;
	}

	public URIFactory getUriFactory() {
		return uriFactory;
	}

	@MCChildElement(order=-1, allowForeign=true)
	public void setUriFactory(URIFactory uriFactory) {
		this.uriFactory = uriFactory;
	}

	public Statistics getStatistics() {
		return statistics;
	}

	@MCAttribute
	public void setJmx(String name){
		jmxRouterName = name;
	}

	public String getJmx(){
		return jmxRouterName;
	}

	public void setBeanName(String s) {
		this.id = s;
	}

	public String getId(){
		return id;
	}
}