package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.security.JwtAuthFilter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Tag(name = "Design", description = "Karten-Design (Stempel)")
@RestController
@RequestMapping("/api/shop")
public class StampDesignController {

    private final ShopRepository shopRepo;

    @Value("${stempelkarte.upload-path:./uploads}")
    private String uploadPath;

    @Value("${stempelkarte.base-url:http://localhost:8080}")
    private String baseUrl;

    public StampDesignController(ShopRepository shopRepo) {
        this.shopRepo = shopRepo;
    }

    private Shop currentShop(Authentication auth) {
        return ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
    }

    public record DesignRequest(String walletStyle, String stampIconType, String stampPreset,
                                String stampColor, String emptyStampStyle) {}

    public record StampIconUploadRequest(String base64, String extension) {}

    @GetMapping("/design")
    public Map<String, Object> getDesign(Authentication auth) {
        Shop shop = currentShop(auth);
        Map<String, Object> map = new HashMap<>();
        map.put("walletStyle", shop.getWalletStyle() != null ? shop.getWalletStyle() : "number");
        map.put("stampIconType", shop.getStampIconType() != null ? shop.getStampIconType() : "preset");
        map.put("stampPreset", shop.getStampPreset() != null ? shop.getStampPreset() : "coffee");
        map.put("stampColor", shop.getStampColor() != null ? shop.getStampColor() : "#6F4E37");
        map.put("emptyStampStyle", shop.getEmptyStampStyle() != null ? shop.getEmptyStampStyle() : "number");
        map.put("stampIconUrl", shop.getStampIconUrl() != null ? shop.getStampIconUrl() : "");
        return map;
    }

    @PutMapping("/design")
    public Map<String, Object> updateDesign(@RequestBody DesignRequest req, Authentication auth) {
        Shop shop = currentShop(auth);
        shop.updateDesign(req.walletStyle(), req.stampIconType(), req.stampPreset(),
                req.stampColor(), req.emptyStampStyle());
        shopRepo.save(shop);
        return getDesign(auth);
    }

    @PostMapping("/stamp-icon")
    public Map<String, String> uploadStampIcon(@RequestBody StampIconUploadRequest req,
                                               Authentication auth) throws IOException {
        Shop shop = currentShop(auth);
        byte[] imageBytes = Base64.getDecoder().decode(req.base64());

        Path uploadDir = Paths.get(uploadPath, "stamp-icons");
        Files.createDirectories(uploadDir);

        String ext = req.extension() != null ? req.extension() : "png";
        String filename = shop.getId() + "." + ext;
        Files.write(uploadDir.resolve(filename), imageBytes);

        String url = baseUrl + "/api/shop/stamp-icons/" + filename;
        shop.setStampIconUrl(url);
        shopRepo.save(shop);
        return Map.of("stampIconUrl", url);
    }

    @GetMapping("/stamp-icons/{filename}")
    public ResponseEntity<byte[]> getStampIcon(@PathVariable String filename) throws IOException {
        Path filePath = Paths.get(uploadPath, "stamp-icons", filename);
        if (!Files.exists(filePath)) {
            return ResponseEntity.notFound().build();
        }
        byte[] bytes = Files.readAllBytes(filePath);
        String contentType = filename.endsWith(".png") ? "image/png" : "image/jpeg";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .body(bytes);
    }
}