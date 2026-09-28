package com.player2.playerengine.commands.base;

/**
 * What a command can touch. Every registration declares one; {@link CommandPolicy} decides who may
 * use which, so a future ruling changes the policy, not 46 registrations.
 */
public enum PermissionClass {
   /** Read-only: reports what the companion sees or remembers, and changes nothing. */
   QUERY,
   /** The companion itself: idle, stop, gestures, equipment, eating, its own behaviour settings. */
   SELF,
   /** Where the companion goes: goto, follow, travel to a structure. */
   MOVE,
   /** Inventory and container changes: give, deposit, withdraw, smelt. */
   ITEMS,
   /** Breaking and placing blocks, including gathering that mines, chops or hunts. */
   WORLD,
   /** Combat: attack, hero, automatic hostile targeting. Never a player (R2). */
   HOSTILE,
   /** Server administration: reloading config, wiping memory, switching the AI bridge. */
   ADMIN
}
