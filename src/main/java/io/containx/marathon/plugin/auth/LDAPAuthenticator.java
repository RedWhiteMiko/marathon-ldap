package io.containx.marathon.plugin.auth;

import akka.dispatch.ExecutionContexts;
import akka.dispatch.Futures;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import io.containx.marathon.plugin.auth.type.AuthKey;
import io.containx.marathon.plugin.auth.type.Configuration;
import io.containx.marathon.plugin.auth.type.UserIdentity;
import io.containx.marathon.plugin.auth.util.HTTPHelper;
import io.containx.marathon.plugin.auth.util.LDAPHelper;
import mesosphere.marathon.plugin.auth.Authenticator;
import mesosphere.marathon.plugin.auth.Identity;
import mesosphere.marathon.plugin.http.HttpRequest;
import mesosphere.marathon.plugin.http.HttpResponse;
import mesosphere.marathon.plugin.plugin.PluginConfiguration;
import play.api.libs.json.JsObject;
import scala.Option;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class LDAPAuthenticator implements Authenticator, PluginConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(LDAPAuthenticator.class);


    private final ExecutionContext EC = ExecutionContexts.fromExecutorService(Executors.newSingleThreadExecutor());
    private final LoadingCache<AuthKey, UserIdentity> USERS = CacheBuilder.newBuilder()
        .maximumSize(2000)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build(new CacheLoader<AuthKey, UserIdentity>() {
            @Override
            public UserIdentity load(AuthKey key) throws Exception {
                return (UserIdentity) doAuth(key.getUsername(), key.getPassword());
            }
        });

    private Configuration config;

    @Override
    public void initialize(scala.collection.immutable.Map<String, Object> map, JsObject jsObject) {
        try {
            config = new ObjectMapper().readValue(jsObject.toString(), Configuration.class);
        } catch (IOException e) {
            LOGGER.error("Error reading configuration JSON: {}", e.getMessage(), e);
        }
    }

    @Override
    public Future<Option<Identity>> authenticate(HttpRequest request) {
        return Futures.future(() -> Option.apply(doAuth(request)), EC);
    }

    private Identity doAuth(HttpRequest request) {
        try {
            AuthKey ak = HTTPHelper.authKeyFromHeaders(request);
            if (ak != null) {

                // If a user is matched in the static list within the config - validate
                if (config.hasStaticUser(ak.getUsername())) {
                    UserIdentity su = config.getStaticUser(ak.getUsername());
                    if (ak.getPassword().equals(su.getPassword())) {
                        return su;
                    }
                    return null;
                }

                // Use LDAP for non-static users
                UserIdentity id = USERS.get(ak);
                if (id != null) {
                    return id.applyResolvePermissions(config);
                }
            }
        } catch (Exception ex) {
            LOGGER.error("LDAP error validating user: {}", ex.getMessage());
        }
        return null;
    }

    /**
     * Authenticate, if the username matches the password.
     */
    private Identity doAuth(String username, String password) {
        Set<String> memberships = LDAPHelper.validate(username, password, config.getLdap());
        if (memberships != null) {
            return new UserIdentity(username, memberships).applyResolvePermissions(config);
        } else {
            return null;
        }
    }


    @Override
    public void handleNotAuthenticated(HttpRequest request, HttpResponse response) {
        HTTPHelper.applyNotAuthenticatedToResponse(response);
    }
}
