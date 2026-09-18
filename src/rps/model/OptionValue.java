package rps.model;

public record OptionValue(
    int id,
    int groupId,
    String name,
    int displayOrder
) {
}
