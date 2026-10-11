// Sends one text message over a WebSocket and prints the first answer (ET20): java WsEcho.java <ws-url> <user:token> <message>
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public class WsEcho {
    public static void main(String[] args) throws Exception {
        CompletableFuture<String> answer = new CompletableFuture<>();
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(args[1].getBytes()))
                .buildAsync(URI.create(args[0]), new WebSocket.Listener() {
                    StringBuilder text = new StringBuilder();
                    @Override
                    public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                        text.append(data);
                        if (last) {
                            answer.complete(text.toString());
                        }
                        w.request(1);
                        return null;
                    }
                    @Override
                    public void onError(WebSocket w, Throwable error) {
                        answer.completeExceptionally(error);
                    }
                }).get(20, TimeUnit.SECONDS);
        ws.sendText(args[2], true).join();
        System.out.println(answer.get(20, TimeUnit.SECONDS));
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }
}
