package pro.gravit.launcher.events.request;

import pro.gravit.launcher.ClientPermissions;
import pro.gravit.launcher.LauncherNetworkAPI;
import pro.gravit.launcher.events.RequestEvent;
import pro.gravit.launcher.profiles.PlayerProfile;

import java.util.UUID;

public class AuthRequestEvent extends RequestEvent {
    public static final String TWO_FACTOR_NEED_ERROR_MESSAGE = "auth.require2fa";
    public static final String ONE_FACTOR_NEED_ERROR_MESSAGE_PREFIX = "auth.require.factor.";
    public static final String USER_NOT_FOUND_ERROR_MESSAGE = "auth.message.usernotfound";
    public static final String WRONG_PASSWORD_ERROR_MESSAGE = "auth.message.wrongpassword";
    public static final String ACCOUNT_BLOCKED_ERROR_MESSAGE = "auth.message.blocked";
    @LauncherNetworkAPI
    public ClientPermissions permissions;
    @LauncherNetworkAPI
    public PlayerProfile playerProfile;
    @LauncherNetworkAPI
    public String accessToken;
    @LauncherNetworkAPI
    public String protectToken;
    @LauncherNetworkAPI
    public UUID session;

    public AuthRequestEvent() {
    }

    public AuthRequestEvent(PlayerProfile pp, String accessToken, ClientPermissions permissions) {
        this.playerProfile = pp;
        this.accessToken = accessToken;
        this.permissions = permissions;
    }

    public AuthRequestEvent(ClientPermissions permissions, PlayerProfile playerProfile, String accessToken, String protectToken) {
        this.permissions = permissions;
        this.playerProfile = playerProfile;
        this.accessToken = accessToken;
        this.protectToken = protectToken;
    }

    public AuthRequestEvent(ClientPermissions permissions, PlayerProfile playerProfile, String accessToken, String protectToken, UUID session) {
        this.permissions = permissions;
        this.playerProfile = playerProfile;
        this.accessToken = accessToken;
        this.protectToken = protectToken;
        this.session = session;
    }

    @Override
    public String getType() {
        return "auth";
    }
}
