package mips;

public class LswInstr extends MipsInstr {
    private String op;
    private Register rs;
    private Register rt;
    private int immediate;
    private String label = "";

    public LswInstr(String op, Register rs, Register rt, int immediate) {
        this.op = op;
        this.rs = rs;
        this.rt = rt;
        this.immediate = immediate;
        MipsBuilder.addInstr(this);
    }

    public LswInstr(String op, Register rs, String label) {
        this.rs = rs;
        this.op = op;
        this.label = label;
        MipsBuilder.addInstr(this);
    }

    public void print() {
        if (!label.isEmpty()) {
            System.out.println(op+" "+rs.toString()+" "+label);
        }
        else {
            System.out.println(op+" "+rs.toString()+" "+immediate+"("+rt.toString()+")");
        }
    }
}
