package llvm;

import llvm.constant.Constant;

public class GlobalVariable extends GlobalValue {
    private Constant value = null;

    public GlobalVariable(String name) {
        super(ValueType.GLOBAL_VARIABLE, ReturnType.POINTER, "@"+name);
    }

    public boolean isInit() {
        return value != null;
    }

    @Override
    public void setValue(Constant value) {
        this.value = value;
    }

    @Override
    public void print() {
        System.out.println(name+" = dso_local global i32 "+value.getName());
    }
}
