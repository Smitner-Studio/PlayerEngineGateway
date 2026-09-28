package com.player2.playerengine.commands.base;

import java.util.List;

/** A registration named commands with no {@link PermissionClass}; nothing in it was registered. */
public final class UnclassedCommandException extends IllegalStateException {
   private final List<String> commandNames;

   public UnclassedCommandException(List<String> commandNames) {
      super("Commands registered without a permission class: " + commandNames);
      this.commandNames = List.copyOf(commandNames);
   }

   public List<String> commandNames() {
      return this.commandNames;
   }
}
