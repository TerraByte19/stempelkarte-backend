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
import java.util.Map;

@Tag(name = "Logo", description = "Logo-Upload fuer Laden")
@RestController
@RequestMapping("/api/shop")
public class LogoController {

    private final ShopRepository shopRepo;

    @Value("${stempelkarte.upload-path:./uploads}")
    private String uploadPath;

    @Value("${stempelkarte.base-url:http://localhost:8080}")
    private String baseUrl;

    public LogoController(ShopRepository shopRepo) {
        this.shopRepo = shopRepo;
    }

    // Request: { "base64": "iVBORw0KGgo...", "extension": "png" }
    public record LogoUploadRequest(String base64, String extension) {}

    @PostMapping("/logo")
    public Map<String, String> uploadLogo(@RequestBody LogoUploadRequest req,
                                          Authentication auth) throws IOException {
        System.out.println("=== Logo Upload Start ===");

        Shop shop = ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();
        System.out.println("Shop: " + shop.getId());

        byte[] imageBytes = Base64.getDecoder().decode(req.base64());

        Path uploadDir = Paths.get(uploadPath, "logos");
        Files.createDirectories(uploadDir);

        String ext = req.extension() != null ? req.extension() : "png";
        String filename = shop.getId() + "." + ext;
        Path filePath = uploadDir.resolve(filename);
        Files.write(filePath, imageBytes);

        System.out.println("Gespeichert: " + filePath.toAbsolutePath());

        String logoUrl = baseUrl + "/api/shop/logos/" + filename;
        shop.update(null, logoUrl, null, null, null);
        shopRepo.save(shop);

        System.out.println("Logo URL: " + logoUrl);
        return Map.of("logoUrl", logoUrl);
    }

    @GetMapping("/logos/{filename}")
    public ResponseEntity<byte[]> getLogo(@PathVariable String filename) throws IOException {
        Path filePath = Paths.get(uploadPath, "logos", filename);
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