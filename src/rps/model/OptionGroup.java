package rps.model;

import java.util.List;

public record OptionGroup(
    int id,
    String name,
    int displayOrder,
    List<OptionValue> values
) {
    @Override
    public String toString() {
        return name;
    }
}
