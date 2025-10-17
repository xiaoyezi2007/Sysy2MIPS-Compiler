package llvm;

import java.util.ArrayList;

public class User extends Value {
    public ArrayList<Value> values = new ArrayList<>();

    public User(ValueType valueType, ReturnType Type, String name) {
        super(valueType, Type, name);
    }

    public void addUseValue(Value value) {
        values.add(value);
        value.addUser(this);
        value.addUse(new Use(this, value));
    }
}
