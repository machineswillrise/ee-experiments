///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.eclipse.jetty.ee10:jetty-ee10-servlet:12.1.10
//DEPS org.slf4j:slf4j-simple:2.0.16

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import jakarta.servlet.ServletException;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

import java.io.IOException;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;

class OrderServlet extends HttpServlet {
	private Map<String, String> storeOrders = new HashMap<>() {{
		put("John", "Water");
		put("Jane", "Coffee");
		put("Bob", "Tea");
	}};

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		StringBuilder html = new StringBuilder();
		html.append(
			"""
			<html>
				<body>
					<h1>Orders</h1>
					<ul>
			"""
		);

		for (Map.Entry<String, String> entry : storeOrders.entrySet()) {
			String customer = entry.getKey();
			String product = entry.getValue();

			html.append("<li>\n")
				.append("\t").append(customer).append(": ")
				.append(product).append("\n")
				.append("</li>\n");
		}

		html.append(
			"""
					</ul>
				</body>
			</html>
			"""
		);
		
		resp.getWriter().println(html);
	}
}

class MyFilter implements Filter {
	public MyFilter() {
	}

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		// ...
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
	throws IOException, ServletException {
		HttpServletResponse httpResponse = (HttpServletResponse) response;
		chain.doFilter(request, httpResponse);
	}

	@Override
	public void destroy() {
		// ...
	}
}

class InitResult {
	private final Server server;
	private ServletContextHandler context;

	public InitResult(Server server, ServletContextHandler context) {
		this.server = server;
		this.context = context;
	}

	public Server getServer() {
		return server;
	}

	public ServletContextHandler getContext() {
		return context;
	}

	public void setContext() {
		this.context = context;
	}
}

public class Restaurant {
	private static final Logger LOG = LoggerFactory.getLogger(Restaurant.class);

	private static final String ROUTE = "/orders";
	private static final int PORT = 8080;

	private static InitResult init(Class<?> servlet, String route, int port)
	throws InstantiationException, IllegalAccessException, NoSuchMethodException, InvocationTargetException {
		Server server = new Server();
		ServerConnector connector = new ServerConnector(server);
		connector.setPort(port);
		connector.setHost("localhost");
		server.addConnector(connector);

		ServletContextHandler context = new ServletContextHandler();
		context.setContextPath("/");
		server.setHandler(context);

		HttpServlet servletInstance = (HttpServlet) servlet.getDeclaredConstructor().newInstance();
		ServletHolder holder = new ServletHolder(servletInstance);
		context.addServlet(holder, route);

		LOG.info("Configured Jetty!");
		return new InitResult(server, context);
	}

	private static void configureFilter(InitResult result, Filter filterInstance, String route) {
		FilterHolder holder = new FilterHolder(filterInstance);
		holder.setName(filterInstance.getClass().getSimpleName());
		result.getContext().addFilter(holder, route, null);

		LOG.info("Configured Filters!");
	}

	public static void main(String[] args) {
		InitResult result;
		Server server;

		try {
			result = init(OrderServlet.class, ROUTE, PORT);
			configureFilter(result, new MyFilter(), ROUTE);
			server = result.getServer();
		} catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
			LOG.error("Jetty Configuration Error: " + e.getMessage());
			return;
		}

		try {
			server.start();
			LOG.info("Started Jetty!");
			server.join();
		} catch (Exception e) {
			LOG.error("Jetty Error: " + e.getMessage());
		}
	}
}
