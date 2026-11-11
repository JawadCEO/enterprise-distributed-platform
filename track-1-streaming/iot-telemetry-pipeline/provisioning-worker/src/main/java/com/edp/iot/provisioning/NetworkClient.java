package com.edp.iot.provisioning;

import com.edp.iot.common.Json;
import com.edp.iot.common.request.LineCommand;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * The network's provisioning interface: {@code POST /v1/provision} with the request ID, ICCID and
 * action. The network answers {@code 202} at once and reports the outcome later as a
 * {@code PROVISIONING_RESULT} event through event-ingest. The request ID makes a repeated call harmless.
 */
@Component
class NetworkClient {

    enum Outcome { ACCEPTED, REFUSED, RETRY }

    record Answer(Outcome outcome, String detail) {
    }

    private final HttpClient http;
    private final ProvisioningProperties props;

    NetworkClient(HttpClient networkHttpClient, ProvisioningProperties props) {
        this.http = networkHttpClient;
        this.props = props;
    }

    Answer provision(LineCommand command) {
        String body = Json.write(Map.of("requestId", command.requestId().toString(), "iccid", command.iccid(),
                "action", command.action().name()));
        HttpRequest request = HttpRequest.newBuilder(URI.create(props.networkUrl() + "/v1/provision"))
                .timeout(props.callTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code >= 200 && code < 300) {
                return new Answer(Outcome.ACCEPTED, null);
            }
            if (code == 429 || code >= 500) {
                return new Answer(Outcome.RETRY, "HTTP " + code);
            }
            return new Answer(Outcome.REFUSED, "HTTP " + code);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Answer(Outcome.RETRY, "interrupted");
        } catch (Exception e) {
            return new Answer(Outcome.RETRY, e.getClass().getSimpleName());
        }
    }
}
