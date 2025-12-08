package llvm.instr;

import llvm.Builder;
import llvm.IRType;
import llvm.Value;
import llvm.ValueType;

import java.util.ArrayList;

public class ZextInstr extends Instruction{
    public ZextInstr(IRType type, Value from) {
        super(ValueType.ZEXT_INST, type, Builder.getVarName());
        addUseValue(from);
        Builder.addInstr(this);
    }

    public void toMips() {
        Value from = getUseValue(0);
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
}
