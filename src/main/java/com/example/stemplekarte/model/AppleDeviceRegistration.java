package com.example.stemplekarte.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "apple_device_registrations")
@IdClass(AppleDeviceRegistration.PK.class)
public class AppleDeviceRegistration {

    @Id
    @Column(name = "device_library_identifier", length = 128)
    private String deviceLibraryIdentifier;

    @Id
    @Column(name = "serial_number", length = 64)
    private String serialNumber;

    @Column(name = "push_token", nullable = false, length = 256)
    private String pushToken;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    protected AppleDeviceRegistration() {}

    public static AppleDeviceRegistration of(String deviceId, String serial, String pushToken) {
        AppleDeviceRegistration r = new AppleDeviceRegistration();
        r.deviceLibraryIdentifier = deviceId;
        r.serialNumber = serial;
        r.pushToken = pushToken;
        r.registeredAt = Instant.now();
        return r;
    }

    public String getDeviceLibraryIdentifier() { return deviceLibraryIdentifier; }
    public String getSerialNumber() { return serialNumber; }
    public String getPushToken() { return pushToken; }
    public Instant getRegisteredAt() { return registeredAt; }

    public static class PK implements Serializable {
        private String deviceLibraryIdentifier;
        private String serialNumber;

        public PK() {}
        public PK(String d, String s) { this.deviceLibraryIdentifier = d; this.serialNumber = s; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(deviceLibraryIdentifier, pk.deviceLibraryIdentifier)
                    && Objects.equals(serialNumber, pk.serialNumber);
        }

        @Override
        public int hashCode() { return Objects.hash(deviceLibraryIdentifier, serialNumber); }
    }
}