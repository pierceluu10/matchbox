package dev.matchbox.bank;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

/** A real HTTP endpoint standing in for Matchbox's settlement webhook. */
final class WebhookReceiver implements AutoCloseable {

	private final HttpServer server;

	final List<Delivery> deliveries = new CopyOnWriteArrayList<>();

	WebhookReceiver() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		this.server.createContext("/settlements", (exchange) -> {
			try (InputStream body = exchange.getRequestBody()) {
				this.deliveries.add(new Delivery(new String(body.readAllBytes(), StandardCharsets.UTF_8),
						exchange.getRequestHeaders().getFirst(WebhookSigner.SIGNATURE_HEADER)));
			}
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		this.server.start();
	}

	String url() {
		return "http://localhost:" + this.server.getAddress().getPort() + "/settlements";
	}

	@Override
	public void close() {
		this.server.stop(0);
	}

	record Delivery(String body, String signature) {
	}

}
