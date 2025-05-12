package org.datatransferproject.datatransfer.sdata.common;

import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.ClientParametersAuthentication;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.CredentialRefreshListener;
import com.google.api.client.auth.oauth2.RefreshTokenRequest;
import com.google.api.client.auth.oauth2.TokenErrorResponse;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import java.io.IOException;
import org.datatransferproject.api.launcher.Monitor;
import org.datatransferproject.spi.transfer.types.InvalidTokenException;
import org.datatransferproject.types.transfer.auth.AppCredentials;
import org.datatransferproject.types.transfer.auth.TokensAndUrlAuthData;

/** Factory for creating {@link Credential} objects from {@link TokensAndUrlAuthData}. */
// TODO: sdata optimization: this can probably be emptied/deleted for now, but
// we want to keep it around as we know we'll eventually use it for remote
// sdata storage locations like gcp buckets or AWS stores.
public class SdataCredentialFactory {

  private static final long EXPIRE_TIME_IN_SECONDS = 0L;

  private final HttpTransport httpTransport;
  private final JsonFactory jsonFactory;
  private final AppCredentials appCredentials;
  private final Monitor monitor;

  public SdataCredentialFactory(
      HttpTransport httpTransport,
      JsonFactory jsonFactory,
      AppCredentials appCredentials,
      Monitor monitor) {
    this.httpTransport = httpTransport;
    this.jsonFactory = jsonFactory;
    this.appCredentials = appCredentials;
    this.monitor = monitor;
  }

  public HttpTransport getHttpTransport() {
    return httpTransport;
  }

  public JsonFactory getJsonFactory() {
    return jsonFactory;
  }

  /**
   * Creates a {@link Credential} objects with the given {@link TokensAndUrlAuthData} which supports
   * refreshing tokens.
   */
  public Credential createCredential(TokensAndUrlAuthData authData) {
    return new Credential.Builder(BearerToken.authorizationHeaderAccessMethod())
        .setTransport(httpTransport)
        .setJsonFactory(jsonFactory)
        .setClientAuthentication(
            new ClientParametersAuthentication(appCredentials.getKey(), appCredentials.getSecret()))
        .setTokenServerEncodedUrl(authData.getTokenServerEncodedUrl())
        .addRefreshListener(
            new CredentialRefreshListener() {
              @Override
              public void onTokenResponse(Credential credential, TokenResponse tokenResponse)
                  throws IOException {
                monitor.info(() -> "Successfully refreshed token");
              }

              @Override
              public void onTokenErrorResponse(
                  Credential credential, TokenErrorResponse tokenErrorResponse) throws IOException {
                monitor.info(
                    () -> "Error while refreshing token: " + tokenErrorResponse.getError());
              }
            })
        .build()
        .setAccessToken(authData.getAccessToken())
        .setRefreshToken(authData.getRefreshToken())
        .setExpiresInSeconds(EXPIRE_TIME_IN_SECONDS);
  }

  /** Refreshes and updates the given credential */
  public Credential refreshCredential(Credential credential)
      throws IOException, InvalidTokenException {
    try {
      TokenResponse tokenResponse =
          new RefreshTokenRequest(
                  httpTransport,
                  jsonFactory,
                  new GenericUrl(credential.getTokenServerEncodedUrl()),
                  credential.getRefreshToken())
              .setClientAuthentication(credential.getClientAuthentication())
              .setRequestInitializer(credential.getRequestInitializer())
              .execute();

      return credential.setFromTokenResponse(tokenResponse);
    } catch (TokenResponseException e) {
      TokenErrorResponse details = e.getDetails();
      if (details != null && details.getError().equals("invalid_grant")) {
        throw new InvalidTokenException("Unable to refresh token.", e);
      } else {
        throw e;
      }
    }
  }
}
