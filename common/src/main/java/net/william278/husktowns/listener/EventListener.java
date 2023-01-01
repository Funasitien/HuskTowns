package net.william278.husktowns.listener;

import net.william278.husktowns.HuskTowns;
import net.william278.husktowns.claim.*;
import net.william278.husktowns.network.Broker;
import net.william278.husktowns.town.Member;
import net.william278.husktowns.town.Privilege;
import net.william278.husktowns.town.Town;
import net.william278.husktowns.user.OnlineUser;
import net.william278.husktowns.user.Preferences;
import net.william278.husktowns.user.SavedUser;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

public class EventListener {

    protected final HuskTowns plugin;

    public EventListener(@NotNull HuskTowns plugin) {
        this.plugin = plugin;
    }

    protected boolean cancelOperation(@NotNull Operation operation) {
        if (!plugin.isLoaded()) {
            operation.getUser().ifPresent(onlineUser -> plugin.getLocales().getLocale("error_not_loaded")
                    .ifPresent(onlineUser::sendMessage));
            return true;
        }
        final Optional<TownClaim> claim = plugin.getClaimAt(operation.getPosition());
        if (claim.isPresent()) {
            return cancelOperation(operation, claim.get());
        }
        final Optional<ClaimWorld> world = plugin.getClaimWorld(operation.getPosition().getWorld());
        if (world.isEmpty() && plugin.getRulePresets().getUnclaimableWorldRules().cancelOperation(operation.getType())) {
            if (!operation.isSilent() && operation.getUser().isPresent()) {
                plugin.getLocales().getLocale("operation_cancelled")
                        .ifPresent(operation.getUser().get()::sendMessage);
            }
            return true;
        }
        if (plugin.getRulePresets().getWildernessRules().cancelOperation(operation.getType())) {
            if (!operation.isSilent() && operation.getUser().isPresent()) {
                plugin.getLocales().getLocale("operation_cancelled")
                        .ifPresent(operation.getUser().get()::sendMessage);
            }
            return true;
        }
        return false;
    }

    private boolean cancelOperation(@NotNull Operation operation, @NotNull TownClaim townClaim) {
        final Optional<OnlineUser> optionalUser = operation.getUser();
        final Town town = townClaim.town();
        final Claim claim = townClaim.claim();

        // If the operation is not allowed by the claim flags
        if (town.getRules().get(claim.getType()).cancelOperation(operation.getType())) {
            if (optionalUser.isEmpty()) {
                return true;
            }

            final OnlineUser user = optionalUser.get();
            final Claim.Type claimType = claim.getType();
            if (claimType == Claim.Type.PLOT && claim.isPlotMember(user.getUuid())) {
                return false;
            }

            final Optional<Member> optionalMember = plugin.getUserTown(user);
            if (optionalMember.isEmpty()) {
                if (!operation.isSilent()) {
                    plugin.getLocales().getLocale("operation_cancelled_claimed",
                            town.getName()).ifPresent(user::sendMessage);
                }
                return true;
            }

            final Member member = optionalMember.get();
            if (!member.town().equals(town)) {
                if (!operation.isSilent()) {
                    plugin.getLocales().getLocale("operation_cancelled_claimed",
                            town.getName()).ifPresent(user::sendMessage);
                }
                return true;
            }

            if (!member.hasPrivilege(plugin, Privilege.TRUSTED_ACCESS)) {
                if (!operation.isSilent()) {
                    plugin.getLocales().getLocale("operation_cancelled_privileges")
                            .ifPresent(user::sendMessage);
                }
                return true;
            }
            return false;
        }
        return false;
    }

    protected boolean cancelNature(@NotNull Chunk chunk1, @NotNull Chunk chunk2,
                                   @NotNull World world) {
        if (plugin.getClaimWorld(world).isEmpty()) {
            return false;
        }
        final Optional<TownClaim> claim1 = plugin.getClaimAt(chunk1, world);
        final Optional<TownClaim> claim2 = plugin.getClaimAt(chunk2, world);
        if (claim1.isPresent() && claim2.isPresent()) {
            return !claim1.get().town().equals(claim2.get().town());
        }
        return !(claim1.isEmpty() && claim2.isEmpty());
    }

    protected void onPlayerJoin(@NotNull OnlineUser user) {
        plugin.runAsync(() -> {
            final Optional<SavedUser> userData = plugin.getDatabase().getUser(user.getUuid());
            if (userData.isEmpty()) {
                plugin.getDatabase().createUser(user, Preferences.getDefaults());
                plugin.setUserPreferences(user.getUuid(), Preferences.getDefaults());
                return;
            }

            // Update the user's name if it has changed
            final SavedUser savedUser = userData.get();
            if (!savedUser.user().getUsername().equals(user.getUsername())) {
                plugin.getDatabase().updateUser(user, savedUser.preferences());
                if (savedUser.preferences().isTownChatTalking()) {
                    plugin.getLocales().getLocale("town_chat_reminder")
                            .ifPresent(user::sendMessage);
                }
            }

            if (plugin.getSettings().crossServer
                && plugin.getSettings().brokerType == Broker.Type.PLUGIN_MESSAGE
                && plugin.getOnlineUsers().size() == 1) {
                plugin.setLoaded(false);
                plugin.loadData();
            }
        });
    }

    protected void onPlayerQuit(@NotNull OnlineUser user) {
        plugin.runAsync(() -> plugin.getUserPreferences(user.getUuid())
                .ifPresent(preferences -> plugin.getDatabase().updateUser(user, preferences)));
    }

    protected void onPlayerInspect(@NotNull OnlineUser user, @NotNull Position position) {
        final Optional<TownClaim> claim = plugin.getClaimAt(position);
        if (claim.isPresent()) {
            final TownClaim townClaim = claim.get();
            final Claim claimData = townClaim.claim();
            plugin.highlightClaim(user, townClaim);
            plugin.getLocales().getLocale("inspect_chunk_claimed_" + claimData.getType().name().toLowerCase(),
                            Integer.toString(claimData.getChunk().getX()), Integer.toString(claimData.getChunk().getZ()),
                            townClaim.town().getName())
                    .ifPresent(user::sendMessage);
            return;
        }
        if (plugin.getClaimWorld(user.getWorld()).isEmpty()) {
            plugin.getLocales().getLocale("inspect_chunk_not_claimable")
                    .ifPresent(user::sendMessage);
            return;
        }
        plugin.getLocales().getLocale("inspect_chunk_not_claimed")
                .ifPresent(user::sendMessage);
    }

    public void handleChunkChange(@NotNull OnlineUser user, @NotNull Position from, @NotNull Position to) {
        final Optional<TownClaim> fromClaim = plugin.getClaimAt(from);
        final Optional<TownClaim> toClaim = plugin.getClaimAt(to);
        if (fromClaim.map(TownClaim::town).equals(toClaim.map(TownClaim::town))) {
            return;
        }

        //todo
    }

    public boolean handlePlayerChat(@NotNull OnlineUser user, @NotNull String message) {
        final Optional<Preferences> preferences = plugin.getUserPreferences(user.getUuid());
        if (preferences.isPresent() && preferences.get().isTownChatTalking()) {
            plugin.getManager().towns().sendChatMessage(user, message);
            return true;
        }
        return false;
    }
}
