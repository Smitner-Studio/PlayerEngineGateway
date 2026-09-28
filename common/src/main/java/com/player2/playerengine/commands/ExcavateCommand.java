package com.player2.playerengine.commands;

import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.tasks.construction.area.AreaScan;

public class ExcavateCommand extends AreaCommand {
    public ExcavateCommand() throws CommandException {
        super("excavate",
                "Dig out a box of ground in survival: a room, cellar, tunnel section or clearing. "
                        + "`excavate <dx> <dy> <dz> [anchor=here|owner|last|x,y,z] [facing=north|south|east|west]` "
                        + "puts the floor at the anchor's feet and starts one block ahead of it (dx wide, dz deep, "
                        + "dy high); a coordinate anchor centres the box on that block. "
                        + "`excavate <x1> <y1> <z1> <x2> <y2> <z2>` digs between two corners. At most 32 wide, "
                        + "8 high and about 20 minutes of digging per command; bigger jobs are several steps. "
                        + "Leaves blocks people placed and containers standing, refuses next to water or lava, "
                        + "and reports the finished area so `anchor=last` or corners can extend it.",
                AreaScan.Mode.EXCAVATE);
    }
}
