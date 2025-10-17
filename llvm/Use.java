package llvm;

public class Use {
    private User user;
    private Value value;

    public Use(User user, Value value) {
        this.user = user;
        this.value = value;
    }

    public User getUser() {
        return user;
    }

    public Value getValue() {
        return value;
    }

}
