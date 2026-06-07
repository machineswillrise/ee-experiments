///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.eclipse.jetty.ee10:jetty-ee10-servlet:12.1.10
//DEPS org.eclipse.jetty.ee10:jetty-ee10-servlets:12.1.10
//DEPS org.eclipse.jetty:jetty-server:12.1.10
//DEPS org.apache.johnzon:johnzon-mapper:2.1.0
//DEPS org.glassfish:jakarta.json:2.0.1
//DEPS org.slf4j:slf4j-simple:2.0.16
//DEPS org.xerial:sqlite-jdbc:3.50.2.0
//DEPS jakarta.validation:jakarta.validation-api:3.1.1
//DEPS org.hibernate.validator:hibernate-validator:8.0.2.Final
//DEPS org.glassfish:jakarta.el:4.0.0

import jakarta.servlet.ServletException;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import jakarta.validation.Validator;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.ConstraintViolation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;

import java.io.IOException;
import java.io.PrintWriter;

import java.nio.file.Path;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;

import org.apache.johnzon.mapper.Mapper;
import org.apache.johnzon.mapper.MapperBuilder;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;

import org.eclipse.jetty.server.handler.ResourceHandler;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

record Order(@NotBlank String name, @NotBlank String product, @NotNull @Min(1)BigDecimal price, Timestamp timestamp) {}

class OrderServlet extends HttpServlet {
	private final Connection connection;

	private final Mapper mapper = new MapperBuilder().setAccessModeName("method").build();
	private final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
	private final Validator validator = factory.getValidator();

	public OrderServlet(Connection connection) {
		this.connection = connection;
	}	

	private Order extractFromParams(HttpServletRequest req) {
		String name = req.getParameter("name");
		String product = req.getParameter("product");
		BigDecimal price = new BigDecimal(req.getParameter("price"));

		long timestampMillis = Long.parseLong(req.getParameter("timestamp"));
		Timestamp timestamp = new Timestamp(timestampMillis);
		Order order = new Order(name, product, price, timestamp);

		return order;
	}

	private boolean validate(Order order, HttpServletResponse response) {
		Set<ConstraintViolation<Order>> violations = validator.validate(order);
		if (!violations.isEmpty()) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			return false;
		}
		return true;
	}

	private void setUpStmt(Order order, PreparedStatement stmt) throws SQLException {
		stmt.setString(1, order.name());
		stmt.setString(2, order.product());
		stmt.setBigDecimal(3, order.price());
		stmt.setTimestamp(4, order.timestamp());
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp)
	throws ServletException, IOException {
		resp.setContentType("application/json");
		List<Order> orders = new ArrayList<>();
		try (PreparedStatement stmt = connection.prepareStatement("SELECT name, product, price, timestamp FROM orders");
			ResultSet rs = stmt.executeQuery()) {
			while (rs.next()) {
				orders.add(
					new Order(rs.getString("name"), rs.getString("product"),
						rs.getBigDecimal("price"), rs.getTimestamp("timestamp"))
				);
			}
		} catch (SQLException e) {
			throw new ServletException(e);
		}
		try (PrintWriter writer = resp.getWriter()) {
			mapper.writeObject(orders, writer);
		}
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp)
	throws ServletException, IOException {
		Order order = extractFromParams(req);
		if (!validate(order, resp)) {
			return;
		}

		try (PreparedStatement stmt = connection.prepareStatement(
				"INSERT INTO orders (name, product, price, timestamp) VALUES (?, ?, ?, ?)")) {
			setUpStmt(order, stmt);
			stmt.execute();
		} catch (SQLException e) {
			throw new ServletException(e);
		}
		resp.setStatus(HttpServletResponse.SC_OK);
	}

	@Override
	protected void doDelete(HttpServletRequest req, HttpServletResponse resp)
	throws ServletException, IOException {
		Order order = extractFromParams(req);
		if (!validate(order, resp)) {
			return;
		}

		try (PreparedStatement stmt = connection.prepareStatement(
				"DELETE FROM orders WHERE name = ? AND product = ? AND price = ? AND timestamp = ?")) {
			setUpStmt(order, stmt);
			stmt.execute();
		} catch (SQLException e) {
			throw new ServletException(e);
		}
		resp.setStatus(HttpServletResponse.SC_OK);
	}

	public void closeValidator() {
		factory.close();
	}
}

record InitResult(Server server, ServletContextHandler context) {}

public class Restaurant {
	private static final Logger LOG = LoggerFactory.getLogger(Restaurant.class);

	private static final String ROUTE = "/orders";
	private static final int PORT = 8080;
	private static final Path STATIC_FILE_PATH = Path.of("static");

	private static InitResult init(HttpServlet servletInstance, String route, int port) {
		Server server = new Server();
		ServerConnector connector = new ServerConnector(server);
		connector.setPort(port);
		connector.setHost("localhost");
		server.addConnector(connector);

		ServletContextHandler context = new ServletContextHandler();
		context.setContextPath("/");
		server.setHandler(context);

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


	private static void close(Statement statement, Connection connection) {
		try {
			statement.close();
			connection.close();
		} catch (SQLException e) {
			LOG.error("Database Close Error: " + e.getMessage());
		}
	}

	public static void main(String[] args) {
		InitResult result;
		Server server;
		OrderServlet servlet;

		Connection connection;
		Statement statement;
		try {
			connection = DriverManager.getConnection("jdbc:sqlite:restaurant.db");
			statement = connection.createStatement();
			statement.setQueryTimeout(10);
			statement.execute("CREATE TABLE IF NOT EXISTS orders (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, product TEXT, price DECIMAL, timestamp TIMESTAMP)");
		} catch (SQLException e) {
			LOG.error("Database Connection Error: " + e.getMessage());
			return;
		}

		try {
			servlet = new OrderServlet(connection);
			result = init(servlet, ROUTE, PORT);
			configureStaticFiles(result.context(), STATIC_FILE_PATH);
			server = result.server();
		} catch (Exception e) {
			LOG.error("Jetty Configuration Error", e);
			return;
		}

		try {
			server.start();
			LOG.info("Started Jetty!");
			server.join();
		} catch (Exception e) {
			LOG.error("Jetty Error: " + e.getMessage());
		} finally {
			close(statement, connection);
			servlet.closeValidator();
		}
	}
}
