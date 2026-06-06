///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.eclipse.jetty.ee10:jetty-ee10-servlet:12.1.10
//DEPS org.eclipse.jetty.ee10:jetty-ee10-servlets:12.1.10
//DEPS org.eclipse.jetty:jetty-server:12.1.10
//DEPS org.apache.johnzon:johnzon-mapper:2.1.0
//DEPS org.glassfish:jakarta.json:2.0.1
//DEPS org.slf4j:slf4j-simple:2.0.16

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import java.io.IOException;
import java.io.PrintWriter;

import java.nio.file.Path;

import java.lang.reflect.InvocationTargetException;

import java.util.ArrayList;
import java.util.List;

import org.apache.johnzon.mapper.Mapper;
import org.apache.johnzon.mapper.MapperBuilder;

import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;

import org.eclipse.jetty.server.handler.ResourceHandler;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

record Order(String customer, String product) {}

class OrderServlet extends HttpServlet {
	private final List<Order> orders = new ArrayList<>(
		List.of(
			new Order("Bob", "Water"),
			new Order("Bill", "Tea"),
			new Order("Jane", "Coffee")
		)
	);
	private final Mapper mapper = new MapperBuilder().build();	

	private Order extractFromParams(HttpServletRequest req) {
		String customer = req.getParameter("customer");
		String product = req.getParameter("product");
		return new Order(customer, product);
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp)
	throws ServletException, IOException {
		resp.setContentType("application/json");
		try (PrintWriter writer = resp.getWriter()) {
			mapper.writeObject(orders, writer);
		}
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp)
	throws ServletException, IOException {
		Order order = extractFromParams(req);
		orders.add(order);
		resp.setStatus(HttpServletResponse.SC_OK);
	}

	@Override
	protected void doDelete(HttpServletRequest req, HttpServletResponse resp)
	throws ServletException, IOException {
		Order order = extractFromParams(req);
		orders.remove(order);
		resp.setStatus(HttpServletResponse.SC_OK);
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

	public void setContext(ServletContextHandler context) {
		this.context = context;
	}
}

public class Restaurant {
	private static final Logger LOG = LoggerFactory.getLogger(Restaurant.class);

	private static final String ROUTE = "/orders";
	private static final int PORT = 8080;
	private static final Path STATIC_FILE_PATH = Path.of("static");

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

	private static void configureStaticFiles(ServletContextHandler context, Path staticPath) {
		ResourceHandler resourceHandler = new ResourceHandler();
		Resource base = ResourceFactory.of(resourceHandler).newResource(staticPath);
		resourceHandler.setBaseResource(base);
		context.insertHandler(resourceHandler);

		LOG.info("Configured static file serving!");
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
			configureStaticFiles(result.getContext(), STATIC_FILE_PATH);
			server = result.getServer();
		} catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
			LOG.error("Jetty Configuration Error", e);
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
