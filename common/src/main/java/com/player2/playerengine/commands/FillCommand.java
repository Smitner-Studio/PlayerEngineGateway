package com.player2.playerengine.commands;

import com.player2.playerengine.commands.base.CommandException;
import com.player2.playerengine.tasks.construction.area.AreaScan;

public class FillCommand extends AreaCommand {
    public FillCommand() throws CommandException {
        super("fill",
                "Place a block from the inventory over every empty cell of a box: a floor (dy 1), a wall "
                        + "(one side 1) or a plug. `fill <block> <dx> <dy> <dz> [anchor=here|owner|last|x,y,z] "
                        + "[facing=...]` or `fill <block> <x1> <y1> <z1> <x2> <y2> <z2>`. At most 512 blocks; "
                        + "fails with the exact shortfall when the inventory holds too few, so get them first.",
                AreaScan.Mode.FILL);
    }
}
