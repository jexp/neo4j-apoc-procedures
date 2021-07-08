package apoc.util.google.cloud;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.apache.commons.lang3.StringUtils;
import org.neo4j.util.VisibleForTesting;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class GCStorageURLConnection extends URLConnection {

    enum AuthType { NONE, PRIVATE_KEY, GCP_ENVIRONMENT }

    private Blob blob;
    public GCStorageURLConnection(URL url) {
        super(url);
    }

    @VisibleForTesting
    public Storage getStorage(URI uri) {
        Storage storage;
        Map<String, String> queryParams = getQueryParams(uri);
        AuthType authenticationType = AuthType.valueOf(queryParams.getOrDefault("authenticationType", AuthType.NONE.toString()));
        switch (authenticationType) {
            case PRIVATE_KEY:
                String googleAppCredentialsEnv = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
                if (StringUtils.isBlank(googleAppCredentialsEnv)) {
                    throw new RuntimeException("You must set the env variable GOOGLE_APPLICATION_CREDENTIALS as described here: https://cloud.google.com/storage/docs/reference/libraries#client-libraries-install-java");
                }
                // fall through
            case GCP_ENVIRONMENT:
                storage = StorageOptions.getDefaultInstance().getService();
                break;
            default:
                storage = StorageOptions.getUnauthenticatedInstance().getService();
        }
        return storage;
    }

    private Map<String, String> getQueryParams(URI uri) {
        Map<String, String> queryParams;
        if (StringUtils.isBlank(uri.getQuery())) {
            queryParams = Collections.emptyMap();
        } else {
            queryParams = Stream.of(uri.getQuery().split("&"))
                    .map(e -> e.split("="))
                    .collect(Collectors.toMap(e -> e[0], e -> e[1]));
        }
        return queryParams;
    }

    @Override
    public void connect() {
        try {
            URI uri = url.toURI();
            if (StringUtils.isBlank(uri.getPath())) {
                throw new RuntimeException("Please provide the file name");
            }
            blob = getStorage(uri).get(BlobId.of(uri.getAuthority(), uri.getPath().substring(1)));
            connected = true;
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getContentType() {
        return blob.getContentType();
    }

    @Override
    public InputStream getInputStream() throws IOException {
        if (!connected) {
            connect();
        }
        InputStream in;
        if (blob == null) {
            in = new ByteArrayInputStream(new byte[0]);
        } else {
            in = Channels.newInputStream(blob.reader());
        }
        return in;
    }

}
