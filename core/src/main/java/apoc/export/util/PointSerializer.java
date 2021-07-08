package apoc.export.util;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.neo4j.graphdb.spatial.Point;
import org.neo4j.values.storable.CoordinateReferenceSystem;

import java.io.IOException;
import java.util.List;

public class PointSerializer extends JsonSerializer<Point> {
    @Override
    public void serialize(Point value, JsonGenerator jsonGenerator, SerializerProvider serializers) throws IOException {

        String crsType = value.getCRS().getType();
        List<Double> coordinate = value.getCoordinate().getCoordinate();

        if (crsType.startsWith(CoordinateReferenceSystem.Cartesian.toString())) {
            if (coordinate.size() == 3) {
                jsonGenerator.writeObject(new PointCartesian(crsType, coordinate.get(0), coordinate.get(1), coordinate.get(2)));
            } else {
                jsonGenerator.writeObject(new PointCartesian(crsType, coordinate.get(0), coordinate.get(1)));
            }
        } else {
            if (coordinate.size() == 3) {
                jsonGenerator.writeObject(new PointWgs(crsType, coordinate.get(0), coordinate.get(1), coordinate.get(2)));
            } else {
                jsonGenerator.writeObject(new PointWgs(crsType, coordinate.get(0), coordinate.get(1)));
            }
        }
    }


    class PointCartesian {
        private String crs;
        private Double x;
        private Double y;
        private Double z;

        public PointCartesian(String crs, Double x, Double y, Double z) {
            this.crs = crs;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public PointCartesian(String crs, Double x, Double y) {
            this.crs = crs;
            this.x = x;
            this.y = y;
        }

        public String getCrs() {
            return crs;
        }

        public void setCrs(String crs) {
            this.crs = crs;
        }

        public Double getX() {
            return x;
        }

        public void setX(Double x) {
            this.x = x;
        }

        public Double getY() {
            return y;
        }

        public void setY(Double y) {
            this.y = y;
        }

        public Double getZ() {
            return z;
        }

        public void setZ(Double z) {
            this.z = z;
        }
    }


    class PointWgs {
        private String crs;
        private Double latitude;
        private Double longitude;
        private Double height;

        public PointWgs(String crs, Double longitude, Double latitude, Double height) {
            this.crs = crs;
            this.latitude = latitude;
            this.longitude = longitude;
            this.height = height;
        }

        public PointWgs(String crs, Double longitude, Double latitude) {
            this.crs = crs;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        public String getCrs() {
            return crs;
        }

        public void setCrs(String crs) {
            this.crs = crs;
        }

        public Double getLatitude() {
            return latitude;
        }

        public void setLatitude(Double latitude) {
            this.latitude = latitude;
        }

        public Double getLongitude() {
            return longitude;
        }

        public void setLongitude(Double longitude) {
            this.longitude = longitude;
        }

        public Double getHeight() {
            return height;
        }

        public void setHeight(Double height) {
            this.height = height;
        }
    }

}