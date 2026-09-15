package co.wethinkcode.healthsafe;

public record Ward(String wardId, String wing, String department, Integer bedsAvailable, String notes) {
}
