package mips.fake;

import mips.MipsBuilder;
import mips.MipsInstr;
import mips.Register;

public class LaInstr extends MipsInstr {
    private Register to;
    private String addr;

    public LaInstr(Register to, String addr) {
        this.to = to;
        this.addr = addr;
        MipsBuilder.addInstr(this);
    }

    public void print() {
        System.out.println("la " + to.toString() + " " + addr);
    }
}
