package mips;

public class RInstr extends MipsInstr {
    private Register left;
    private Register right;
    private Register ans;

    public RInstr(String op, Register ans, Register left, Register right) {
        this.ans = ans;
        this.left = left;
        this.right = right;
        this.op = op;
        MipsBuilder.addInstr(this);
    }

    public void print() {
        System.out.println(op+" "+ans.toString()+" "+left.toString()+" "+right.toString());
    }
}
