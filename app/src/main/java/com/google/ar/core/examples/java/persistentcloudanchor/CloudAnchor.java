package com.google.ar.core.examples.java.persistentcloudanchor;

import com.google.ar.core.Anchor;

import java.util.Objects;

public class CloudAnchor {
    private final String id;

    public String getName() {
        return name;
    }

    private final String name;

    private final double lat;
    private final double lon;
    private final double alt;

    public CloudAnchor(String id, String name, double lat, double lon, double alt) {
        this.id = id;
        this.name = name;
        this.lat = lat;
        this.lon = lon;
        this.alt = alt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CloudAnchor that = (CloudAnchor) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    public String getId(){return this.id;}

    public double getDistance(CloudAnchor a1)
    {
        return 0;
    }
}
