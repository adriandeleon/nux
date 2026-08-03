package com.nux.protocol;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * A blocking NUT client: one TCP connection to upsd (default port 3493), optionally
 * upgraded to TLS via the protocol's STARTTLS command.
 *
 * <p>Not thread-safe — intended to be owned by a single polling thread. Every read is
 * bounded by the socket timeout so a hung server can never wedge the caller.
 */
public final class NutClient implements Closeable {

    public static final int DEFAULT_PORT = 3493;

    private final Socket socket;
    private final BufferedReader in;
    private final BufferedWriter out;

    private NutClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    public static NutClient connect(String host, int port, Duration timeout) throws IOException {
        return connect(host, port, timeout, TlsMode.DISABLED);
    }

    public static NutClient connect(String host, int port, Duration timeout, TlsMode tls) throws IOException {
        int millis = (int) Math.min(Integer.MAX_VALUE, timeout.toMillis());
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), millis);
            socket.setSoTimeout(millis);
            if (tls != TlsMode.DISABLED) {
                socket = startTls(socket, host, port, millis, tls);
            }
            return new NutClient(socket);
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    /**
     * Sends STARTTLS and wraps the socket in TLS. The pre-TLS reply is read unbuffered,
     * byte by byte — a BufferedReader could steal bytes that belong to the TLS handshake.
     */
    private static Socket startTls(Socket plain, String host, int port, int timeoutMillis, TlsMode mode)
            throws IOException {
        OutputStream rawOut = plain.getOutputStream();
        rawOut.write("STARTTLS\n".getBytes(StandardCharsets.UTF_8));
        rawOut.flush();
        String reply = readLineRaw(plain.getInputStream());
        if (!reply.startsWith("OK")) {
            throw new IOException("Server refused STARTTLS: " + reply
                    + " (upsd needs CERTFILE configured to serve TLS)");
        }
        try {
            SSLContext context;
            if (mode == TlsMode.REQUIRED_TRUST_ALL) {
                context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[] {TRUST_ALL}, null);
            } else {
                context = SSLContext.getDefault();
            }
            SSLSocketFactory factory = context.getSocketFactory();
            SSLSocket ssl = (SSLSocket) factory.createSocket(plain, host, port, true);
            if (mode == TlsMode.REQUIRED) {
                SSLParameters params = ssl.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS"); // hostname verification
                ssl.setSSLParameters(params);
            }
            ssl.setSoTimeout(timeoutMillis);
            ssl.startHandshake();
            return ssl;
        } catch (GeneralSecurityException e) {
            throw new IOException("TLS setup failed: " + e.getMessage(), e);
        }
    }

    private static String readLineRaw(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                return buf.toString(StandardCharsets.UTF_8);
            }
            buf.write(b);
        }
        throw new EOFException("Connection closed by server");
    }

    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    /** All UPSes this upsd serves. */
    public List<UpsDevice> listUps() throws IOException {
        List<UpsDevice> devices = new ArrayList<>();
        for (String line : listBlock("LIST UPS")) {
            UpsDevice d = NutParser.parseUpsLine(line);
            if (d != null) {
                devices.add(d);
            }
        }
        return devices;
    }

    /** Every variable of one UPS, in server order. */
    public LinkedHashMap<String, String> listVars(String ups) throws IOException {
        LinkedHashMap<String, String> vars = new LinkedHashMap<>();
        for (String line : listBlock("LIST VAR " + ups)) {
            UpsVar v = NutParser.parseVarLine(line);
            if (v != null) {
                vars.put(v.name(), v.value());
            }
        }
        return vars;
    }

    /** The instant commands one UPS supports ({@code beeper.toggle}, ...). */
    public List<String> listCmds(String ups) throws IOException {
        List<String> cmds = new ArrayList<>();
        for (String line : listBlock("LIST CMD " + ups)) {
            List<String> t = NutParser.tokens(line);
            if (t.size() >= 3 && t.get(0).equals("CMD")) {
                cmds.add(t.get(2));
            }
        }
        return cmds;
    }

    /**
     * The writable variables of one UPS, fully described (type, choices, range) so the
     * UI can offer a safe editor. One round trip per variable for the type, plus one
     * more for enum/range detail — fine for the handful of RW vars a UPS exposes.
     */
    public List<RwVariable> listRw(String ups) throws IOException {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (String line : listBlock("LIST RW " + ups)) {
            List<String> t = NutParser.tokens(line);
            if (t.size() >= 4 && t.get(0).equals("RW")) {
                values.put(t.get(2), t.get(3));
            }
        }
        List<RwVariable> out = new ArrayList<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            out.add(describeRw(ups, e.getKey(), e.getValue()));
        }
        return out;
    }

    private RwVariable describeRw(String ups, String name, String value) throws IOException {
        List<String> typeTokens = varType(ups, name);
        RwVariable.Kind kind = RwVariable.kindOf(typeTokens);
        int maxLength = RwVariable.maxLengthOf(typeTokens);
        return switch (kind) {
            case ENUM -> new RwVariable(name, value, kind, listEnum(ups, name), null, null, maxLength);
            case RANGE -> {
                double[] range = listRange(ups, name);
                yield new RwVariable(
                        name, value, kind, List.of(),
                        range == null ? null : range[0], range == null ? null : range[1], maxLength);
            }
            default -> new RwVariable(name, value, kind, List.of(), null, null, maxLength);
        };
    }

    /** The type flag tokens of a variable ({@code ENUM}, {@code STRING:8}, {@code NUMBER}, ...). */
    public List<String> varType(String ups, String name) throws IOException {
        send("GET TYPE " + ups + " " + name);
        String line = readLine();
        if (NutParser.isErr(line)) {
            throw new NutException(NutParser.errCode(line));
        }
        List<String> t = NutParser.tokens(line);
        return t.size() > 3 ? List.copyOf(t.subList(3, t.size())) : List.of();
    }

    public List<String> listEnum(String ups, String name) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : listBlock("LIST ENUM " + ups + " " + name)) {
            List<String> t = NutParser.tokens(line);
            if (t.size() >= 4 && t.get(0).equals("ENUM")) {
                out.add(t.get(3));
            }
        }
        return out;
    }

    /** The {@code [min, max]} of a RANGE variable, or null when the server reports none. */
    public double[] listRange(String ups, String name) throws IOException {
        for (String line : listBlock("LIST RANGE " + ups + " " + name)) {
            List<String> t = NutParser.tokens(line);
            if (t.size() >= 5 && t.get(0).equals("RANGE")) {
                try {
                    return new double[] {Double.parseDouble(t.get(3)), Double.parseDouble(t.get(4))};
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Writes a variable (needs auth); the value is quoted so spaces survive. */
    public void setVar(String ups, String name, String value) throws IOException {
        expectOk("SET VAR " + ups + " " + name + " \"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
    }

    public String getVar(String ups, String name) throws IOException {
        send("GET VAR " + ups + " " + name);
        String line = readLine();
        if (NutParser.isErr(line)) {
            throw new NutException(NutParser.errCode(line));
        }
        UpsVar v = NutParser.parseVarLine(line);
        if (v == null) {
            throw new IOException("Unexpected reply: " + line);
        }
        return v.value();
    }

    public void username(String user) throws IOException {
        expectOk("USERNAME " + user);
    }

    public void password(String password) throws IOException {
        expectOk("PASSWORD " + password);
    }

    /** Runs an instant command (beeper.toggle, test.battery.start.quick, ...); needs auth. */
    public void instCmd(String ups, String command) throws IOException {
        expectOk("INSTCMD " + ups + " " + command);
    }

    private void expectOk(String command) throws IOException {
        send(command);
        String line = readLine();
        if (NutParser.isErr(line)) {
            throw new NutException(NutParser.errCode(line));
        }
        if (!line.startsWith("OK")) {
            throw new IOException("Unexpected reply: " + line);
        }
    }

    private List<String> listBlock(String query) throws IOException {
        send(query);
        String first = readLine();
        if (NutParser.isErr(first)) {
            throw new NutException(NutParser.errCode(first));
        }
        if (!first.startsWith("BEGIN LIST")) {
            throw new IOException("Unexpected reply: " + first);
        }
        List<String> lines = new ArrayList<>();
        while (true) {
            String line = readLine();
            if (line.startsWith("END LIST")) {
                return lines;
            }
            lines.add(line);
        }
    }

    private void send(String command) throws IOException {
        out.write(command);
        out.write('\n');
        out.flush();
    }

    private String readLine() throws IOException {
        String line = in.readLine();
        if (line == null) {
            throw new EOFException("Connection closed by server");
        }
        return line;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // the original failure is the interesting exception
        }
    }

    @Override
    public void close() {
        try {
            send("LOGOUT");
        } catch (IOException ignored) {
            // best-effort courtesy; the socket close below is what matters
        }
        closeQuietly(socket);
    }
}
