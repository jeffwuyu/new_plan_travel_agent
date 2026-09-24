package com.travelagent.service.routemap;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

@Component
public class RouteMapImageRenderer {

    public byte[] renderSkeleton(Map<String, Object> geometry, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        paintBase(g, width, height);
        paintRoute(g, geometry, width, height, false);
        paintStops(g, geometry, width, height);
        g.dispose();
        return toPng(image);
    }

    public byte[] renderOverlay(byte[] baseImage, Map<String, Object> geometry, int width, int height) {
        try {
            BufferedImage base = ImageIO.read(new ByteArrayInputStream(baseImage));
            if (base == null) {
                return renderSkeleton(geometry, width, height);
            }
            BufferedImage canvas = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
            configure(g);
            g.drawImage(base, 0, 0, canvas.getWidth(), canvas.getHeight(), null);
            paintRoute(g, geometry, canvas.getWidth(), canvas.getHeight(), true);
            paintStops(g, geometry, canvas.getWidth(), canvas.getHeight());
            g.dispose();
            return toPng(canvas);
        } catch (Exception e) {
            return renderSkeleton(geometry, width, height);
        }
    }

    @SuppressWarnings("unchecked")
    private void paintRoute(Graphics2D g, Map<String, Object> geometry, int width, int height, boolean overlay) {
        configure(g);
        List<Map<String, Object>> segments = (List<Map<String, Object>>) geometry.getOrDefault("segments", List.of());
        g.setColor(overlay ? new Color(36, 95, 166, 220) : new Color(28, 93, 153));
        g.setStroke(new BasicStroke(overlay ? 9f : 7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (Map<String, Object> segment : segments) {
            Object polylineObj = segment.get("polyline");
            if (!(polylineObj instanceof List<?> polyline) || polyline.size() < 2) {
                continue;
            }
            Point prev = null;
            for (Object pointObj : polyline) {
                if (!(pointObj instanceof Map<?, ?> pointMap)) continue;
                Point point = project(pointMap.get("lng"), pointMap.get("lat"), geometry, width, height);
                if (prev != null && point != null) {
                    g.drawLine(prev.x, prev.y, point.x, point.y);
                }
                prev = point;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void paintStops(Graphics2D g, Map<String, Object> geometry, int width, int height) {
        configure(g);
        List<Map<String, Object>> stops = (List<Map<String, Object>>) geometry.getOrDefault("stops", List.of());
        Font labelFont = new Font("Microsoft YaHei", Font.BOLD, Math.max(24, width / 64));
        Font numberFont = new Font("SansSerif", Font.BOLD, Math.max(24, width / 62));
        for (Map<String, Object> stop : stops) {
            Point p = project(stop.get("lng"), stop.get("lat"), geometry, width, height);
            if (p == null) continue;
            int radius = Math.max(24, width / 70);
            g.setColor(new Color(255, 255, 255, 238));
            g.fillOval(p.x - radius, p.y - radius, radius * 2, radius * 2);
            g.setColor(new Color(217, 74, 61));
            g.setStroke(new BasicStroke(5f));
            g.drawOval(p.x - radius, p.y - radius, radius * 2, radius * 2);
            g.setFont(numberFont);
            String order = String.valueOf(stop.get("order"));
            FontMetrics nf = g.getFontMetrics();
            g.drawString(order, p.x - nf.stringWidth(order) / 2, p.y + nf.getAscent() / 2 - 4);

            g.setFont(labelFont);
            String name = String.valueOf(stop.getOrDefault("name", ""));
            FontMetrics lf = g.getFontMetrics();
            int labelX = Math.min(Math.max(24, p.x + radius + 12), width - lf.stringWidth(name) - 24);
            int labelY = Math.min(Math.max(lf.getAscent() + 24, p.y - radius - 8), height - 24);
            g.setColor(new Color(255, 255, 255, 220));
            g.fillRoundRect(labelX - 10, labelY - lf.getAscent() - 6, lf.stringWidth(name) + 20, lf.getHeight() + 10, 14, 14);
            g.setColor(new Color(35, 48, 63));
            g.drawString(name, labelX, labelY);
        }
    }

    private void paintBase(Graphics2D g, int width, int height) {
        configure(g);
        g.setColor(new Color(244, 248, 246));
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(223, 232, 230));
        g.setStroke(new BasicStroke(2f));
        int gap = Math.max(96, width / 14);
        for (int x = gap; x < width; x += gap) {
            g.drawLine(x, 0, x - gap / 3, height);
        }
        for (int y = gap; y < height; y += gap) {
            g.drawLine(0, y, width, y - gap / 4);
        }
    }

    @SuppressWarnings("unchecked")
    private Point project(Object lngObj, Object latObj, Map<String, Object> geometry, int width, int height) {
        Double lng = toDouble(lngObj);
        Double lat = toDouble(latObj);
        if (lng == null || lat == null) return null;
        Map<String, Object> bounds = (Map<String, Object>) geometry.get("bounds");
        double minLng = toDouble(bounds.get("minLng"));
        double maxLng = toDouble(bounds.get("maxLng"));
        double minLat = toDouble(bounds.get("minLat"));
        double maxLat = toDouble(bounds.get("maxLat"));
        double x = (lng - minLng) / Math.max(0.000001d, maxLng - minLng);
        double y = 1.0d - ((lat - minLat) / Math.max(0.000001d, maxLat - minLat));
        return new Point((int) Math.round(x * width), (int) Math.round(y * height));
    }

    private Double toDouble(Object value) {
        if (value == null) return null;
        return Double.parseDouble(value.toString());
    }

    private void configure(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    private byte[] toPng(BufferedImage image) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Route map PNG render failed", e);
        }
    }
}
