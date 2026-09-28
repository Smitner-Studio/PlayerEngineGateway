package com.player2.playerengine.commands.base;

import com.player2.playerengine.companion.NoPvp;
import java.util.List;
import java.util.function.Predicate;

/**
 * The one policy every player is under (R1): any player may use every class except
 * {@link PermissionClass#ADMIN} on any companion, with no owner or stranger branch. ADMIN needs a
 * server operator. No caller, operator or not, may aim a {@link PermissionClass#HOSTILE} command at a
 * player (R2); the {@code attack} command and the hit path keep their own refusals behind this one.
 */
public final class CommandPolicy {
   /** The refusal code a denied line carries, so the model and the logs can tell it from a failure. */
   public static final String DENIED = "denied";

   private CommandPolicy() {
   }

   /**
    * Why {@code caller} may not run command {@code name} of class {@code permissionClass} with
    * {@code args}, or null when it may.
    *
    * @param namesAPlayer whether an argument names a player (an online player's name, or "player")
    */
   public static String refusal(String name, PermissionClass permissionClass, List<String> args,
         CommandCaller caller, Predicate<String> namesAPlayer) {
      if (permissionClass == PermissionClass.ADMIN && (caller == null || !caller.operator())) {
         return DENIED + ": " + name + " is for server operators only.";
      }
      if (permissionClass == PermissionClass.HOSTILE) {
         for (String arg : args) {
            if (namesAPlayer.test(unquote(arg))) {
               return DENIED + ": " + NoPvp.REFUSAL;
            }
         }
      }
      return null;
   }

   private static String unquote(String arg) {
      String a = arg.trim();
      if (a.length() >= 2 && (a.startsWith("\"") && a.endsWith("\"") || a.startsWith("'") && a.endsWith("'"))) {
         return a.substring(1, a.length() - 1);
      }
      return a;
   }
}
