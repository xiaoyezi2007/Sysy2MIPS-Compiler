package llvm.instr;

import llvm.Builder;
import llvm.IRType;
import llvm.Value;
import llvm.ValueType;
import llvm.constant.Constant;
import llvm.constant.ConstantInt;
import mips.Register;

import java.util.ArrayList;

public class ZextInstr extends Instruction{
    public ZextInstr(IRType type, Value from) {
        super(ValueType.ZEXT_INST, type, Builder.getVarName());
        addUseValue(from);
        Builder.addInstr(this);
    }

    public void toMips() {
        Value from = getUseValue(0);
        if (from instanceof Instruction) {
            Instruction instr = (Instruction) from;
            if (!instr.isSpilled() && instr.getAssignedRegister() != null) {
                assignRegister(instr.getAssignedRegister());
                return;
            }
        }
        this.memory = from.getMemPos();
    }

    public void print() {
        Value from = getUseValue(0);
        System.out.println(name+" = zext "+ from.getTypeName()+" "+from.getName()+" to "+Type.toString());
    }

    @Override
    public ArrayList<String> tripleString() {
        ArrayList<String> ans = new ArrayList<>();
        Value from = getUseValue(0);
        ans.add("zext "+from.getName()+" 32");
        return ans;
    }

    @Override
    public Constant getValue() {
        Value from = getUseValue(0);
        Constant c = from.getValue();
        if (!(c instanceof ConstantInt)) {
            return null;
        }
        return new ConstantInt(Integer.parseInt(c.getName()));
    }
}
