package org.datatransferproject.datatransfer.sdata;

import static org.datatransferproject.types.common.models.DataVertical.MEDIA;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import org.datatransferproject.api.launcher.ExtensionContext;
import org.datatransferproject.api.launcher.Monitor;
import org.datatransferproject.datatransfer.sdata.common.SdataCredentialFactory;
import org.datatransferproject.datatransfer.sdata.media.SdataMediaExporter;
import org.datatransferproject.spi.cloud.storage.AppCredentialStore;
import org.datatransferproject.spi.cloud.storage.JobStore;
import org.datatransferproject.spi.transfer.extension.TransferExtension;
import org.datatransferproject.spi.transfer.provider.Exporter;
import org.datatransferproject.spi.transfer.provider.Importer;
import org.datatransferproject.types.common.models.DataVertical;
import org.datatransferproject.types.transfer.auth.AppCredentials;

/**
 * SdataTransferExtension exposes exporters whose source of data is some static dataset that's
 * pluggable (like a local filesystem, or a remote bucket) so long as said pluggable source confirms
 * to {@link StaticTransfer}.
 */
public class SdataTransferExtension implements TransferExtension {
  public static final String SERVICE_ID = "static-not-a-service";
  private static final ImmutableSet<DataVertical> SUPPORTED_SERVICES = ImmutableSet.of(MEDIA);
  private ImmutableMap<DataVertical, Importer<?, ?>> importerMap;
  private ImmutableMap<DataVertical, Exporter<?, ?>> exporterMap;
  private boolean initialized = false;

  @Override
  public String getServiceId() {
    return SERVICE_ID;
  }

  @Override
  public Exporter<?, ?> getExporter(DataVertical transferDataType) {
    Preconditions.checkArgument(initialized);
    Preconditions.checkArgument(SUPPORTED_SERVICES.contains(transferDataType));
    return exporterMap.get(transferDataType);
  }

  /**
   * WARNING: this will probably never successfully resolve an importer, as Static extension is
   * designed to provide an input (Exporter) for testing and development purposes.
   */
  @Override
  public Importer<?, ?> getImporter(DataVertical transferDataType) {
    Preconditions.checkArgument(initialized);
    Preconditions.checkArgument(SUPPORTED_SERVICES.contains(transferDataType));
    return importerMap.get(transferDataType);
  }

  @Override
  public void initialize(ExtensionContext context) {
    // Note: initialize could be called twice in an account migration scenario where we import and
    // export to the same service provider. So just return rather than throwing if called multiple
    // times.
    if (initialized) {
      return;
    }

    JobStore jobStore = context.getService(JobStore.class);
    HttpTransport httpTransport = context.getService(HttpTransport.class);
    JsonFactory jsonFactory = context.getService(JsonFactory.class);

    // TODO: sdata optimization: eventually we want an abstraction layer here
    // so we can have different static backends (local filesystem, gcp bucket,
    // aws, etc). For now this extension is hardcoded to just one backend,
    // hence this single set of creds.
    AppCredentials appCredentials;
    try {
      appCredentials =
          context
              .getService(AppCredentialStore.class)
              .getAppCredentials("STATIC_TRANSFER_GCP_BUCKETID", "STATIC_TRANSFER_GCP_PROJECTID");
    } catch (IOException e) {
      Monitor monitor = context.getMonitor();
      monitor.info(
          () ->
              "Unable to retrieve StaticTransfer AppCredentials. Did you set"
                  + " STATIC_TRANSFER_GCP_PROJECTID and STATIC_TRANSFER_GCP_BUCKETID?");
      return;
    }

    Monitor monitor = context.getMonitor();

    SdataCredentialFactory credentialFactory =
        new SdataCredentialFactory(httpTransport, jsonFactory, appCredentials, monitor);

    importerMap = ImmutableMap.<DataVertical, Importer<?, ?>>builder().buildOrThrow();

    exporterMap =
        ImmutableMap.<DataVertical, Exporter<?, ?>>builder()
            .put(MEDIA, new SdataMediaExporter(credentialFactory, jobStore, jsonFactory, monitor))
            .buildOrThrow();

    initialized = true;
  }
}
