/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.security.authentication.token;

import java.io.UnsupportedEncodingException;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.jcr.AccessDeniedException;
import javax.jcr.Credentials;
import javax.jcr.RepositoryException;

import com.google.common.collect.ImmutableMap;
import org.apache.jackrabbit.JcrConstants;
import org.apache.jackrabbit.api.security.authentication.token.TokenCredentials;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.api.Tree;
import org.apache.jackrabbit.oak.namepath.NamePathMapper;
import org.apache.jackrabbit.oak.plugins.identifier.IdentifierManager;
import org.apache.jackrabbit.oak.spi.namespace.NamespaceConstants;
import org.apache.jackrabbit.oak.spi.security.ConfigurationParameters;
import org.apache.jackrabbit.oak.spi.security.authentication.ImpersonationCredentials;
import org.apache.jackrabbit.oak.spi.security.authentication.credentials.CredentialsSupport;
import org.apache.jackrabbit.oak.spi.security.authentication.credentials.SimpleCredentialsSupport;
import org.apache.jackrabbit.oak.spi.security.authentication.token.TokenConstants;
import org.apache.jackrabbit.oak.spi.security.authentication.token.TokenInfo;
import org.apache.jackrabbit.oak.spi.security.authentication.token.TokenProvider;
import org.apache.jackrabbit.oak.spi.security.user.UserConfiguration;
import org.apache.jackrabbit.oak.spi.security.user.util.PasswordUtil;
import org.apache.jackrabbit.oak.plugins.tree.TreeUtil;
import org.apache.jackrabbit.util.ISO8601;
import org.apache.jackrabbit.util.Text;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.jackrabbit.oak.api.Type.DATE;
import static org.apache.jackrabbit.oak.api.Type.STRING;
import static org.apache.jackrabbit.oak.plugins.identifier.IdentifierManager.getIdentifier;

/**
 * Default implementation of the {@code TokenProvider} interface that keeps login
 * tokens in the content repository. As a precondition the configured the user
 * management implementation must provide paths for all
 * {@link org.apache.jackrabbit.api.security.user.User users} that refer to
 * a valid {@link Tree} in the content repository.
 * <p>
 * <h3>Backwards compatibility with Jackrabbit 2.x</h3>
 * For security reasons the nodes storing the token information now have a
 * dedicated node type (rep:Token) which has the following definition:
 * <pre>
 *     [rep:Token] > mix:referenceable
 *      - rep:token.key (STRING) protected mandatory
 *      - rep:token.exp (DATE) protected mandatory
 *      - * (UNDEFINED) protected
 *      - * (UNDEFINED) multiple protected
 * </pre>
 * Consequently the hash of the token and the expiration time of tokens generated
 * by this provider can no longer be manipulated using regular JCR item
 * modifications.<p>
 * <p>
 * Existing login tokens generated by Jackrabbit 2.x which are migrated to
 * OAK will still be valid (unless they expire) due to the fact that
 * {@link #getTokenInfo(String)} and the implementation of the {@link TokenInfo}
 * interface will not validate the node type of the token node associated with
 * a given token.
 */
class TokenProviderImpl implements TokenProvider, TokenConstants {

    private static final Logger log = LoggerFactory.getLogger(TokenProviderImpl.class);

    /**
     * Optional configuration parameter to define the number of token nodes that
     * when exceeded will trigger a cleanup of expired tokens upon creation.
     */
    static final String PARAM_TOKEN_CLEANUP_THRESHOLD = "tokenCleanupThreshold";

    /**
     * Default value indicating that tokens should never be cleaned up (i.e.
     * backwards compatible behavior).
     */
    static final long NO_TOKEN_CLEANUP = 0;

    /**
     * Default expiration time in ms for login tokens is 2 hours.
     */
    static final long DEFAULT_TOKEN_EXPIRATION = 2 * 3600 * 1000;
    static final int DEFAULT_KEY_SIZE = 8;

    private static final char DELIM = '_';

    private final Root root;
    private final ConfigurationParameters options;
    private final CredentialsSupport credentialsSupport;

    private final long tokenExpiration;
    private final UserManager userManager;
    private final IdentifierManager identifierManager;
    private final long cleanupThreshold;

    TokenProviderImpl(@NotNull Root root, @NotNull ConfigurationParameters options, @NotNull UserConfiguration userConfiguration) {
        this(root, options, userConfiguration, SimpleCredentialsSupport.getInstance());
    }

    TokenProviderImpl(@NotNull Root root, @NotNull ConfigurationParameters options, @NotNull UserConfiguration userConfiguration, @NotNull CredentialsSupport credentialsSupport) {
        this.root = root;
        this.options = options;
        this.credentialsSupport = credentialsSupport;

        this.tokenExpiration = options.getConfigValue(PARAM_TOKEN_EXPIRATION, DEFAULT_TOKEN_EXPIRATION);
        this.userManager = userConfiguration.getUserManager(root, NamePathMapper.DEFAULT);
        this.identifierManager = new IdentifierManager(root);
        this.cleanupThreshold = options.getConfigValue(PARAM_TOKEN_CLEANUP_THRESHOLD, NO_TOKEN_CLEANUP);
    }

    //------------------------------------------------------< TokenProvider >---

    /**
     * Returns {@code true} if {@code SimpleCredentials} can be extracted from
     * the specified credentials object and that simple credentials object has
     * a {@link #TOKEN_ATTRIBUTE} attribute with an empty value.
     *
     * @param credentials The current credentials.
     * @return {@code true} if the specified credentials or those extracted from
     * {@link ImpersonationCredentials} are supported and and if the (extracted)
     * credentials object contain a {@link #TOKEN_ATTRIBUTE} attribute with an
     * empty value; {@code false} otherwise.
     */
    @Override
    public boolean doCreateToken(@NotNull Credentials credentials) {
        Credentials creds = extractCredentials(credentials);
        if (creds == null) {
            return false;
        } else {
            Object attr = credentialsSupport.getAttributes(creds).get(TOKEN_ATTRIBUTE);
            return (attr != null && attr.toString().isEmpty());
        }
    }

    /**
     * Create a separate token node underneath a dedicated token store within
     * the user home node. That token node contains the hashed token, the
     * expiration time and additional mandatory attributes that will be verified
     * during login.
     *
     * @param credentials The current credentials.
     * @return A new {@code TokenInfo} or {@code null} if the token could not
     *         be created.
     */
    @Nullable
    @Override
    public TokenInfo createToken(@NotNull Credentials credentials) {
        Credentials creds = extractCredentials(credentials);
        String uid = (creds != null) ? credentialsSupport.getUserId(creds) : null;

        TokenInfo tokenInfo = null;
        if (uid != null) {
            Map<String, ?> attributes = credentialsSupport.getAttributes(creds);
            tokenInfo = createToken(uid, attributes);
            if (tokenInfo != null) {
                // also set the new token to the credentials.
                if (!credentialsSupport.setAttributes(creds, ImmutableMap.of(TOKEN_ATTRIBUTE, tokenInfo.getToken()))) {
                    log.debug("Cannot set token attribute to {}", creds);
                }
            }
        }

        return tokenInfo;
    }

    /**
     * Create a separate token node underneath a dedicated token store within
     * the user home node. That token node contains the hashed token, the
     * expiration time and additional mandatory attributes that will be verified
     * during login.
     *
     * @param userId     The identifier of the user for which a new token should
     *                   be created.
     * @param attributes The attributes associated with the new token.
     * @return A new {@code TokenInfo} or {@code null} if the token could not
     *         be created.
     */
    @Nullable
    @Override
    public TokenInfo createToken(@NotNull String userId, @NotNull Map<String, ?> attributes) {
        String error = "Failed to create login token. {}";
        User user = getUser(userId);
        Tree tokenParent = (user == null) ? null : getTokenParent(user);
        if (tokenParent != null) {
            try {
                String id = user.getID();
                long creationTime = System.currentTimeMillis();
                long exp;
                if (attributes.containsKey(PARAM_TOKEN_EXPIRATION)) {
                    exp = Long.parseLong(attributes.get(PARAM_TOKEN_EXPIRATION).toString());
                } else {
                    exp = tokenExpiration;
                }
                long expTime = createExpirationTime(creationTime, exp);
                String uuid = UUID.randomUUID().toString();

                TokenInfo tokenInfo;
                try {
                    String tokenName = uuid;
                    tokenInfo = createTokenNode(tokenParent, tokenName, expTime, uuid, id, attributes);
                    root.commit(CommitMarker.asCommitAttributes());
                } catch (CommitFailedException e) {
                    // conflict while creating token node -> retry
                    log.debug("Failed to create token node. Using random name as fallback.");
                    root.refresh();
                    tokenInfo = createTokenNode(tokenParent, UUID.randomUUID().toString(), expTime, uuid, id, attributes);
                    root.commit(CommitMarker.asCommitAttributes());
                }
                cleanupExpired(userId, tokenParent, creationTime, tokenInfo.getToken());
                return tokenInfo;
            } catch (NoSuchAlgorithmException | UnsupportedEncodingException | CommitFailedException | RepositoryException e) {
                // error while generating login token or while committing changes
                log.error(error, e.getMessage());
            }
        } else {
            log.error("Unable to get/create token store for user {}.", userId);
        }
        return null;
    }

    /**
     * Retrieves the token information associated with the specified login
     * token. If no accessible {@code Tree} exists for the given token or if
     * the token is not associated with a valid user this method returns {@code null}.
     *
     * @param token A valid login token.
     * @return The {@code TokenInfo} associated with the specified token or
     *         {@code null} of the corresponding information does not exist or is not
     *         associated with a valid user.
     */
    @Nullable
    @Override
    public TokenInfo getTokenInfo(@NotNull String token) {
        int pos = token.indexOf(DELIM);
        String nodeId = (pos == -1) ? token : token.substring(0, pos);
        Tree tokenTree = identifierManager.getTree(nodeId);
        if (isValidTokenTree(tokenTree)) {
            try {
                User user = getUser(tokenTree);
                if (user != null) {
                    return new TokenInfoImpl(tokenTree, token, user.getID(), user.getPrincipal());
                }
            } catch (RepositoryException e) {
                log.debug("Cannot determine userID/principal from token: {}", e.getMessage());
            }
        }
        // invalid token tree or failed to extract user or it's id/principal
        return null;
    }

    //--------------------------------------------------------------------------
    private static long createExpirationTime(long creationTime, long tokenExpiration) {
        return creationTime + tokenExpiration;
    }

    private static long getExpirationTime(@NotNull Tree tokenTree, long defaultValue) {
        return TreeUtil.getLong(tokenTree, TOKEN_ATTRIBUTE_EXPIRY, defaultValue);
    }

    private static boolean isExpired(long expirationTime, long loginTime) {
        return expirationTime < loginTime;
    }

    private static void setExpirationTime(@NotNull Tree tree, long time) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(time);
        tree.setProperty(TOKEN_ATTRIBUTE_EXPIRY, ISO8601.format(calendar), DATE);
    }

    @Nullable
    private Credentials extractCredentials(@NotNull Credentials credentials) {
        Credentials creds = credentials;
        if (credentials instanceof ImpersonationCredentials) {
            creds = ((ImpersonationCredentials) credentials).getBaseCredentials();
        }

        if (credentialsSupport.getCredentialClasses().contains(creds.getClass())) {
            return creds;
        } else {
            return null;
        }
    }

    @NotNull
    private static String generateKey(int size) {
        SecureRandom random = new SecureRandom();
        byte key[] = new byte[size];
        random.nextBytes(key);

        StringBuilder res = new StringBuilder(key.length * 2);
        for (byte b : key) {
            res.append(Text.hexTable[(b >> 4) & 15]);
            res.append(Text.hexTable[b & 15]);
        }
        return res.toString();
    }

    @NotNull
    private static String getKeyValue(@NotNull String key, @NotNull String userId) {
        return key + userId;
    }

    private static boolean isValidTokenTree(@Nullable Tree tokenTree) {
        if (tokenTree == null || !tokenTree.exists()) {
            return false;
        } else {
            return TOKENS_NODE_NAME.equals(tokenTree.getParent().getName()) &&
                   TOKEN_NT_NAME.equals(TreeUtil.getPrimaryTypeName(tokenTree));
        }
    }

    @NotNull
    private Tree getTokenTree(@NotNull TokenInfoImpl tokenInfo) {
        return root.getTree(tokenInfo.tokenPath);
    }

    @Nullable
    private User getUser(@NotNull Tree tokenTree) throws RepositoryException {
        String userPath = Text.getRelativeParent(tokenTree.getPath(), 2);
        Authorizable authorizable = userManager.getAuthorizableByPath(userPath);
        if (authorizable != null && !authorizable.isGroup() && !((User) authorizable).isDisabled()) {
            return (User) authorizable;
        } else {
            return null;
        }
    }

    @Nullable
    private User getUser(@NotNull String userId) {
        try {
            Authorizable user = userManager.getAuthorizable(userId);
            if (user != null && !user.isGroup()) {
                return (User) user;
            } else {
                log.debug("Cannot create login token: No corresponding node for User {}.", userId);
            }
        } catch (RepositoryException e) {
            // error while accessing user.
            log.debug("Error while accessing user " + userId + '.', e);
        }
        return null;
    }

    @Nullable
    private Tree getTokenParent(@NotNull User user) {
        Tree tokenParent = null;
        String parentPath = null;
        try {
            String userPath = user.getPath();
            parentPath = userPath + '/' + TOKENS_NODE_NAME;

            Tree userNode = root.getTree(userPath);
            tokenParent = TreeUtil.getOrAddChild(userNode, TOKENS_NODE_NAME, TOKENS_NT_NAME);

            root.commit();
        } catch (RepositoryException e) {
            // error while creating token node.
            log.debug("Error while creating token node {}", e.getMessage());
        } catch (CommitFailedException e) {
            // conflict while creating token store for this user -> refresh and
            // try to get the tree from the updated root.
            log.debug("Conflict while creating token store -> retrying {}", e.getMessage());
            root.refresh();
            Tree parentTree = root.getTree(parentPath);
            if (parentTree.exists()) {
                tokenParent = parentTree;
            } else {
                tokenParent = null;
            }
        }
        return tokenParent;
    }

    /**
     * Create a new token node below the specified {@code parent}.
     *
     * @param parent The parent node.
     * @param expTime The expiration time of the new token.
     * @param uuid The uuid of the token node.
     * @param id The id of the user that issues the token.
     * @param attributes The additional attributes of the token to be created.
     * @return The new token info
     * @throws AccessDeniedException If the editing session cannot access the
     * new token node.
     *
     */
    @NotNull
    private TokenInfo createTokenNode(@NotNull Tree parent, @NotNull String tokenName,
                                      long expTime, @NotNull String uuid,
                                      @NotNull String id, Map<String, ?> attributes)
            throws AccessDeniedException, UnsupportedEncodingException, NoSuchAlgorithmException {

        Tree tokenNode = TreeUtil.addChild(parent, tokenName, TOKEN_NT_NAME);
        tokenNode.setProperty(JcrConstants.JCR_UUID, uuid);

        String key = generateKey(options.getConfigValue(PARAM_TOKEN_LENGTH, DEFAULT_KEY_SIZE));
        String nodeId = getIdentifier(tokenNode);
        String token = nodeId + DELIM + key;

        String keyHash = PasswordUtil.buildPasswordHash(getKeyValue(key, id), options);
        tokenNode.setProperty(TOKEN_ATTRIBUTE_KEY, keyHash);
        setExpirationTime(tokenNode, expTime);

        attributes.forEach((name, value) -> {
            if (!RESERVED_ATTRIBUTES.contains(name)) {
                tokenNode.setProperty(name, value.toString());
            }
        });
        return new TokenInfoImpl(tokenNode, token, id, null);
    }

    /**
     * Remove expired token nodes if the configured threshold (i.e. number of
     * token nodes) is matched/exceeded. By default (i.e. unless configured with
     * a value bigger than {@link #NO_TOKEN_CLEANUP}) no cleanup is performed
     * and this method returns without looking at the token nodes; this makes
     * this addition optional and will not affect existing configurations.
     *
     * @param parent
     *            The token parent node.
     * @param currentTime
     *            The time to be used for analysing expiration of existing
     *            tokens.
     * @param token
     *            The token info used as random data to skip cleanup.
     */
    private void cleanupExpired(@NotNull String userId, @NotNull Tree parent, long currentTime, @NotNull String token) {
        if (cleanupThreshold > NO_TOKEN_CLEANUP && shouldRunCleanup(token)) {
            long start = System.currentTimeMillis();
            long active = 0;
            long expired = 0;
            try {
                if (parent.getChildrenCount(cleanupThreshold) >= cleanupThreshold) {
                    for (Tree child : parent.getChildren()) {
                        if (isExpired(getExpirationTime(child, Long.MIN_VALUE), currentTime)) {
                            expired++;
                            child.remove();
                        } else {
                            active++;
                        }
                    }
                }
                if (root.hasPendingChanges()) {
                    root.commit(CommitMarker.asCommitAttributes());
                }
            } catch (CommitFailedException e) {
                log.debug("Failed to cleanup expired token nodes", e);
                root.refresh();
            } finally {
                if (log.isDebugEnabled() && active + expired > 0) {
                    log.debug("Token cleanup completed in {} ms: removed {}/{} tokens for {}.",
                            System.currentTimeMillis() - start, expired, active + expired, userId);
                }
            }
        }
    }

    /**
     * Method that determines if the cleanup should run or not based on the
     * randomly generated token's first char, this decreases the chances to 1/8.
     *
     * @param token The target token
     * @return true if the cleanup should run
     */
    static boolean shouldRunCleanup(@NotNull String token) {
        return token.charAt(0) < '2';
    }

    // --------------------------------------------------------------------------

    /**
     * TokenInfo
     */
    final class TokenInfoImpl implements TokenInfo {

        private final String token;
        private final String tokenPath;
        private final String userId;
        private final Principal principal;

        private final long expirationTime;
        private final String key;

        private final Map<String, String> mandatoryAttributes;
        private final Map<String, String> publicAttributes;

        private TokenInfoImpl(@NotNull Tree tokenTree, @NotNull String token, @NotNull String userId, @Nullable Principal principal) {
            this.token = token;
            this.tokenPath = tokenTree.getPath();
            this.userId = userId;
            this.principal = principal;

            expirationTime = getExpirationTime(tokenTree, Long.MIN_VALUE);
            key = TreeUtil.getString(tokenTree, TOKEN_ATTRIBUTE_KEY);

            mandatoryAttributes = new HashMap<>();
            publicAttributes = new HashMap<>();
            for (PropertyState propertyState : tokenTree.getProperties()) {
                String name = propertyState.getName();
                String value = propertyState.getValue(STRING);
                if (RESERVED_ATTRIBUTES.contains(name)) {
                    continue;
                }
                if (isMandatoryAttribute(name)) {
                    mandatoryAttributes.put(name, value);
                } else if (isInfoAttribute(name)) {
                    // info attribute
                    publicAttributes.put(name, value);
                } // else: jcr specific property
            }
        }

        @Nullable
        Principal getPrincipal() {
            return principal;
        }

        //------------------------------------------------------< TokenInfo >---

        @NotNull
        @Override
        public String getUserId() {
            return userId;
        }

        @NotNull
        @Override
        public String getToken() {
            return token;
        }

        @Override
        public boolean isExpired(long loginTime) {
            return TokenProviderImpl.isExpired(expirationTime, loginTime);
        }

        @Override
        public boolean resetExpiration(long loginTime) {
            // for backwards compatibility use true as default value for the 'tokenRefresh' configuration
            if (options.getConfigValue(PARAM_TOKEN_REFRESH, true)) {
                Tree tokenTree = getTokenTree(this);
                if (tokenTree.exists()) {
                    if (isExpired(loginTime)) {
                        log.debug("Attempt to reset an expired token.");
                        return false;
                    }

                    if (expirationTime - loginTime <= tokenExpiration / 2) {
                        try {
                            long expTime = createExpirationTime(loginTime, tokenExpiration);
                            setExpirationTime(tokenTree, expTime);
                            root.commit(CommitMarker.asCommitAttributes());
                            log.debug("Successfully reset token expiration time.");
                            return true;
                        } catch (CommitFailedException e) {
                            log.debug("Failed to reset token expiration {}", e.getMessage());
                            root.refresh();
                        }
                    }
                }
            }
            return false;
        }

        @Override
        public boolean remove() {
            Tree tokenTree = getTokenTree(this);
            if (tokenTree.exists()) {
                try {
                    if (tokenTree.remove()) {
                        root.commit(CommitMarker.asCommitAttributes());
                        return true;
                    }
                } catch (CommitFailedException e) {
                    log.debug("Error while removing expired token {}", e.getMessage());
                }
            }
            return false;
        }

        @Override
        public boolean matches(@NotNull TokenCredentials tokenCredentials) {
            String tk = tokenCredentials.getToken();
            int pos = tk.lastIndexOf(DELIM);
            if (pos > -1) {
                tk = tk.substring(pos + 1);
            }
            if (!PasswordUtil.isSame(key, getKeyValue(tk, userId))) {
                return false;
            }

            for (Map.Entry<String,String> mandatory : mandatoryAttributes.entrySet()) {
                String name = mandatory.getKey();
                String expectedValue = mandatory.getValue();
                if (!expectedValue.equals(tokenCredentials.getAttribute(name))) {
                    return false;
                }
            }

            // update set of informative attributes on the credentials
            // based on the properties present on the token node.
            Collection<String> attrNames = Arrays.asList(tokenCredentials.getAttributeNames());
            publicAttributes.forEach((name, value) -> {
                if (!attrNames.contains(name)) {
                    tokenCredentials.setAttribute(name, value);

                }
            });
            return true;
        }

        @NotNull
        @Override
        public Map<String, String> getPrivateAttributes() {
            return Collections.unmodifiableMap(mandatoryAttributes);
        }

        @NotNull
        @Override
        public Map<String, String> getPublicAttributes() {
            return Collections.unmodifiableMap(publicAttributes);
        }

        /**
         * Returns {@code true} if the specified {@code attributeName}
         * starts with or equals {@link #TOKEN_ATTRIBUTE}.
         *
         * @param attributeName The attribute name.
         * @return {@code true} if the specified {@code attributeName}
         *         starts with or equals {@link #TOKEN_ATTRIBUTE}.
         */
        private boolean isMandatoryAttribute(@NotNull String attributeName) {
            return attributeName.startsWith(TOKEN_ATTRIBUTE);
        }

        /**
         * Returns {@code false} if the specified attribute name doesn't have
         * a 'jcr' or 'rep' namespace prefix; {@code true} otherwise. This is
         * a lazy evaluation in order to avoid testing the defining node type of
         * the associated jcr property.
         *
         * @param attributeName The attribute name.
         * @return {@code true} if the specified property name doesn't seem
         *         to represent repository internal information.
         */
        private boolean isInfoAttribute(@NotNull String attributeName) {
            String prefix = Text.getNamespacePrefix(attributeName);
            return !NamespaceConstants.RESERVED_PREFIXES.contains(prefix);
        }
    }
}