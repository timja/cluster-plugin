package cnj;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.htmlunit.FailingHttpStatusCodeException;
import org.htmlunit.HttpMethod;
import org.htmlunit.NicelyResynchronizingAjaxController;
import org.htmlunit.Page;
import org.htmlunit.WebClient;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlTextArea;

/**
 * ET6: a browser (HtmlUnit, JavaScript on) using Jenkins through a load balancer which does not pin it to a controller,
 * so that consecutive requests reach different controllers. Prints one line per check:
 * {@code RESULT ok|fail <description>} and {@code INFO <text>}; exits with the number of failed checks.
 *
 * <p>Arguments: base URL, user, password, new password, freestyle job (with builds), and optionally a shell command
 * that kills one controller, after which the journey continues without logging in again.
 */
public final class Journey {

    private static int failed;
    private static final Set<String> upstreams = new LinkedHashSet<>();

    private static void check(boolean ok, String description) {
        System.out.println("RESULT " + (ok ? "ok" : "fail") + " " + description);
        if (!ok) {
            failed++;
        }
    }

    private static void info(String text) {
        System.out.println("INFO " + text);
    }

    private static void record(WebResponse r) {
        String upstream = r.getResponseHeaderValue("X-Jenkins-Cluster-Controller"); // set by the store plugin on every answer
        if (upstream == null) {
            upstream = r.getResponseHeaderValue("X-Upstream"); // nginx (rr)
        }
        if (upstream != null) {
            upstreams.add(upstream);
        }
    }

    private static WebClient browser() {
        WebClient wc = new WebClient();
        wc.getOptions().setCssEnabled(false);
        wc.getOptions().setThrowExceptionOnScriptError(false);
        wc.getOptions().setPrintContentOnFailingStatusCode(false);
        wc.getOptions().setTimeout(60_000);
        wc.setAjaxController(new NicelyResynchronizingAjaxController());
        return wc;
    }

    private static HtmlPage page(WebClient wc, String url) throws IOException {
        HtmlPage p = wc.getPage(url);
        record(p.getWebResponse());
        wc.waitForBackgroundJavaScript(5_000);
        return p;
    }

    private static String whoAmI(WebClient wc, String base) throws IOException {
        Page p = wc.getPage(base + "whoAmI/api/json");
        record(p.getWebResponse());
        String json = p.getWebResponse().getContentAsString();
        Matcher m = Pattern.compile("\"name\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "?";
    }

    private static boolean login(WebClient wc, String base, String user, String password) throws IOException {
        HtmlPage login = page(wc, base + "login");
        HtmlForm form = login.getFormByName("login");
        form.getInputByName("j_username").setValue(user);
        form.getInputByName("j_password").setValue(password);
        HtmlElement submit = form.getFirstByXPath(".//button[@type='submit'] | .//input[@type='submit']");
        try {
            Page after = submit.click();
            record(after.getWebResponse());
        } catch (FailingHttpStatusCodeException e) {
            info("login answered " + e.getStatusCode());
        }
        return user.equals(whoAmI(wc, base));
    }

    /** Calls a bound object's JavaScript method; returns its status and body. */
    private static String invoke(WebClient wc, String base, String path, String proxyCrumb, String crumbHeader, String crumbValue)
            throws IOException {
        WebRequest call = new WebRequest(new URL(base + path.substring(1)), HttpMethod.POST);
        call.setAdditionalHeader("Crumb", proxyCrumb);
        call.setAdditionalHeader(crumbHeader, crumbValue);
        call.setAdditionalHeader("Content-Type", "application/x-stapler-method-invocation;charset=UTF-8");
        call.setRequestBody("[]");
        WebResponse r = wc.loadWebResponse(call);
        record(r);
        return r.getStatusCode() + " " + r.getContentAsString();
    }

    public static void main(String[] args) throws Exception {
        String base = args[0].endsWith("/") ? args[0] : args[0] + "/";
        String user = args[1];
        String password = args[2];
        String newPassword = args[3];
        String job = args[4];
        String kill = args.length > 5 ? args[5] : null;

        try (WebClient wc = browser()) {
            // 1. log in: the form comes from one controller, the POST may reach the other
            check(login(wc, base, user, password), "logged in through the load balancer");

            // 2. stay signed in whichever controller answers
            int signedIn = 0;
            for (int i = 0; i < 10; i++) {
                if (user.equals(whoAmI(wc, base))) {
                    signedIn++;
                }
            }
            check(signedIn == 10, "signed in on every request (" + signedIn + "/10)");
            check(upstreams.size() >= 2, "requests reached both controllers " + upstreams);

            // 3. a configure page: its bound objects (renderOnDemand) answer wherever the call lands, and the form saves
            HtmlPage cfg = page(wc, base + "job/" + job + "/configure");
            // as Stapler's JavaScript proxy calls them: each object's own crumb, the method-invocation content type
            Matcher m = Pattern.compile("data-proxy-url=\"/\\$stapler/bound/([^\"]+)\" data-proxy-crumb=\"([^\"]+)\"")
                    .matcher(cfg.getWebResponse().getContentAsString());
            Map<String, String> bound = new LinkedHashMap<>();
            while (m.find()) {
                bound.put(m.group(1), m.group(2));
            }
            info("bound objects on the configure page: " + bound.size() + " " + (bound.isEmpty() ? "" : "e.g. " + bound.keySet().iterator().next()));
            HtmlElement head = cfg.getFirstByXPath("//head");
            String crumbHeader = head.getAttribute("data-crumb-header");
            String crumbValue = head.getAttribute("data-crumb-value");
            int answered = 0;
            int calls = 0;
            List<Integer> statuses = new ArrayList<>();
            for (Map.Entry<String, String> e : bound.entrySet().stream().limit(3).toList()) {
                for (int i = 0; i < 4; i++) {
                    WebRequest call = new WebRequest(new URL(base + "$stapler/bound/" + e.getKey() + "/render"), HttpMethod.POST);
                    call.setAdditionalHeader("Crumb", e.getValue());
                    call.setAdditionalHeader(crumbHeader, crumbValue); // as Jenkins adds to its requests
                    call.setAdditionalHeader("Content-Type", "application/x-stapler-method-invocation;charset=UTF-8");
                    call.setRequestBody("[]");
                    WebResponse r = wc.loadWebResponse(call);
                    record(r);
                    calls++;
                    statuses.add(r.getStatusCode());
                    if (r.getStatusCode() < 400) {
                        answered++;
                    }
                }
            }
            check(!bound.isEmpty() && answered == calls, "bound objects answer through either controller (" + answered + "/" + calls + " " + statuses + ")");
            HtmlForm config = cfg.getFormByName("config");
            HtmlTextArea description = config.getTextAreaByName("description");
            description.setText("saved by the browser journey");
            HtmlElement save = config.getFirstByXPath(".//button[@name='Submit']");
            try {
                Page saved = save.click();
                record(saved.getWebResponse());
                check(saved.getWebResponse().getStatusCode() < 400, "configure form saved (" + saved.getWebResponse().getStatusCode() + ")");
            } catch (FailingHttpStatusCodeException e) {
                check(false, "configure form saved (" + e.getStatusCode() + ")");
            }
            // read back through the load balancer: the other controller has it once it applied the change (milliseconds
            // to a second or two), so look for up to 10 s, and say which controllers answered
            boolean there = false;
            java.util.List<String> readers = new java.util.ArrayList<>();
            for (int i = 0; i < 20 && !there; i++) {
                org.htmlunit.Page p = wc.getPage(base + "job/" + job + "/api/json?tree=description");
                readers.add(p.getWebResponse().getResponseHeaderValue("X-Jenkins-Cluster-Controller"));
                there = p.getWebResponse().getContentAsString().contains("saved by the browser journey");
                if (!there) {
                    Thread.sleep(500);
                }
            }
            check(there, "the saved description is there (" + readers.size() + " read(s), from " + readers + ")");

            // 4. progressive rendering (build time trend), as its JavaScript drives it (HtmlUnit has no fetch()):
            // the proxy script, then start() and news() until done, all on the controller holding the renderer
            HtmlPage trend = page(wc, base + "job/" + job + "/buildTimeTrend");
            String html = trend.getWebResponse().getContentAsString();
            Matcher sm = Pattern.compile("\\$stapler/bound/script(/\\$stapler/bound/[^?\"]+)\\?var=[a-zA-Z]+&(?:amp;)?methods=([a-z,]+)").matcher(html);
            if (!sm.find()) {
                check(false, "build time trend has a progressive renderer");
            } else {
                String proxyUrl = sm.group(1);
                WebResponse script = wc.loadWebResponse(new WebRequest(new URL(base + "$stapler/bound/script" + proxyUrl + "?var=proxy&methods=" + sm.group(2))));
                record(script);
                Matcher cm = Pattern.compile("makeStaplerProxy\\('[^']*',(?:'([^']*)'|document\\.head\\.dataset\\.crumbValue)").matcher(script.getContentAsString());
                boolean served = script.getStatusCode() == 200 && cm.find();
                if (!served) {
                    String js = script.getContentAsString();
                    info("proxy script: " + js.substring(0, Math.min(300, js.length())));
                }
                check(served, "progressive renderer's proxy script served (" + script.getStatusCode() + ")");
                HtmlElement th = trend.getFirstByXPath("//head");
                String proxyCrumb = cm.find(0) && cm.group(1) != null ? cm.group(1) : th.getAttribute("data-crumb-value");
                String status = invoke(wc, base, proxyUrl + "/start", proxyCrumb, th.getAttribute("data-crumb-header"), th.getAttribute("data-crumb-value"));
                String news = "";
                for (int i = 0; i < 30 && !news.contains("\"done\""); i++) {
                    Thread.sleep(500);
                    news = invoke(wc, base, proxyUrl + "/news", proxyCrumb, th.getAttribute("data-crumb-header"), th.getAttribute("data-crumb-value"));
                }
                check(status.startsWith("200") && news.contains("\"done\"") && news.contains("#1"),
                        "build time trend rendered progressively (start " + status + ", news " + news.substring(0, Math.min(120, news.length())) + ")");
            }

            // 5. change the password on the user's security page, then log in with the new one
            HtmlPage security = page(wc, base + "user/" + user + "/security/");
            HtmlForm sec = security.getFormByName("config");
            sec.getInputByName("user.password").setValue(newPassword);
            sec.getInputByName("user.password2").setValue(newPassword);
            try {
                Page done = ((HtmlElement) sec.getFirstByXPath(".//button[@name='Submit']")).click();
                record(done.getWebResponse());
            } catch (FailingHttpStatusCodeException e) {
                info("password form answered " + e.getStatusCode());
            }
            // as on one controller, changing the password renews the user's seed, signing out every browser (this one too)
            info("after changing the password, the browser which changed it is signed in: " + user.equals(whoAmI(wc, base)));
            try (WebClient old = browser()) {
                check(!login(old, base, user, password), "the old password no longer works");
            }
            try (WebClient fresh = browser()) {
                check(login(fresh, base, user, newPassword), "logged in with the new password");

                // 6. lose a controller: the browser stays signed in
                if (kill != null) {
                    Process p = new ProcessBuilder("sh", "-c", kill).inheritIO().start();
                    p.waitFor();
                    info("ran: " + kill);
                    int after = 0;
                    for (int i = 0; i < 5; i++) {
                        try {
                            if (user.equals(whoAmI(fresh, base))) {
                                after++;
                            }
                        } catch (IOException | RuntimeException e) {
                            info("request after the kill failed: " + e);
                        }
                    }
                    check(after == 5, "still signed in after losing a controller (" + after + "/5)");
                }
            }
        }
        info("upstreams seen: " + upstreams);
        System.exit(failed);
    }
}
