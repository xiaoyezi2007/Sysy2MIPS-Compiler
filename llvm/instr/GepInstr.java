package llvm.instr;

import llvm.Builder;
import llvm.GlobalVariable;
import llvm.IRType;
import llvm.Value;
import llvm.ValueType;
import llvm.constant.Constant;
import llvm.constant.ConstantInt;
import mips.IInstr;
import mips.RInstr;
import mips.Register;
import mips.fake.LaInstr;

public class GepInstr extends Instruction {
    public GepInstr(Value base, Value index) {
        super(ValueType.GEP_INST, new IRType("ptr", new IRType("i32")), Builder.getVarName());
        addUseValue(base);
        addUseValue(index);
        Builder.addInstr(this);
    }

    @Override
    public int getSpace() {
        return 4;
    }

    @Override
    public void toMips() {
        Value base = getUseValue(0);
        Value index = getUseValue(1);
        if (base instanceof GlobalVariable) {
            new LaInstr(Register.T0, base.getName().substring(1));
            loadToReg(index, Register.T1);
            new IInstr("sll", Register.T1, Register.T1, 2);
            new RInstr("addu", Register.T2, Register.T0, Register.T1);
            pushToMem(Register.T2);
            Type.isAddr = true;
        }
        else if (base.getType().isAddr) {
            loadToReg(base, Register.T0);
            loadToReg(index, Register.T1);
            new IInstr("sll", Register.T1, Register.T1, 2);
            new RInstr("addu", Register.T2, Register.T0, Register.T1);
            pushToMem(Register.T2);
            Type.isAddr = true;
        }
        else {
            loadAddrToReg(base,Register.T0);
            loadToReg(index, Register.T1);
            new IInstr("sll", Register.T1, Register.T1, 2);
            new RInstr("addu", Register.T2, Register.T0, Register.T1);
            pushToMem(Register.T2);
            Type.isAddr = true;
        }
    }

    public void print() {
        Value base = getUseValue(0);
        Value index = getUseValue(1);
        System.out.print(name+" = getelementptr inbounds "+base.getType().ptTo().toString()
        +", "+base.getTypeName()+" "+base.getName()+", ");
        if (base.getType().ptTo().isArray()) {
            System.out.print("i32 0, ");
        }
        System.out.println(index.getTypeName()+" "+index.getName());
    }

    public Constant getValue() {
        Value index = getUseValue(1);
        Constant id = index.getValue();
        if (id == null) return null;
        Value base = getUseValue(0);
        if (base instanceof AllocaInstr) {
            return ((AllocaInstr) base).getKthEle(id).getValue();
        }
        else if (base instanceof GlobalVariable) {
            return ((GlobalVariable) base).getKthEle(id).getValue();
        }
        return null;
    }
}
