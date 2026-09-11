package co.wethinkcode.healthsafe;

public class WardRecord {
    private final String wardId;
    private final String wing;
    private final String department;
    private final Integer bedsAvailable; // null if missing/invalid
    private final String notes;          // null if nothing to flag

    public WardRecord(String wardId, String wing, String department, Integer bedsAvailable, String notes) {
        this.wardId = wardId;
        this.wing = wing;
        this.department = department;
        this.bedsAvailable = bedsAvailable;
        this.notes = notes;
    }

    public String getWardId() { return wardId; }
    public String getWing() { return wing; }
    public String getDepartment() { return department; }
    public Integer getBedsAvailable() { return bedsAvailable; }
    public String getNotes() { return notes; }
}
