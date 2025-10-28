package mips;

import java.util.stream.StreamSupport;

public class IInstr extends MipsInstr {
    private Register rs;
    private Register rt = null;
    private Integer immediate;
    private String label = "";

    public IInstr(String op, Register rs, Register rt, int immediate) {
        this.rs = rs;
        this.rt = rt;
        this.immediate = immediate;
        this.op = op;
        MipsBuilder.addInstr(this);
        if (op.equals("addi") && rs.equals(Register.SP) && rt.equals(Register.SP)) {
            MipsBuilder.memory += immediate;
        }
        else if (op.equals("subi") && rs.equals(Register.SP) && rt.equals(Register.SP)) {
            MipsBuilder.memory -= immediate;
        }
    }

    public IInstr(String op, Register rs, int immediate, String label) {
        this.op = op;
        this.rs = rs;
        this.immediate = immediate;
        this.label = label;
        MipsBuilder.addInstr(this);
    }

    public void print() {
        if (op.equals("sw") || op.equals("lw")) {
            System.out.println(op+" "+rs.toString()+" "+ immediate +"("+rt.toString()+")");
        }
        else if (!label.isEmpty()) {
            System.out.println(op+" "+rs.toString()+" "+ immediate +" "+label);
        }
        else {
            System.out.println(op+" "+rs.toString()+" "+rt.toString()+" "+immediate);
        }
    }
}
