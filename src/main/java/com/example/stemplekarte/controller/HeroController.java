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

@Tag(name = "Hero", description = "Hero-Image-Upload fuer Laden")
@RestController
@RequestMapping("/api/shop")
public class HeroController {

    private final ShopRepository shopRepo;

    @Value("${stempelkarte.upload-path:./uploads}")
    private String uploadPath;

    @Value("${stempelkarte.base-url:http://localhost:8080}")
    private String baseUrl;

    public HeroController(ShopRepository shopRepo) {
        this.shopRepo = shopRepo;
    }

    public record HeroUploadRequest(String base64, String extension) {}

    @PostMapping("/hero")
    public Map<String, String> uploadHero(@RequestBody HeroUploadRequest req,
                                          Authentication auth) throws IOException {
        Shop shop = ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();

        byte[] imageBytes = Base64.getDecoder().decode(req.base64());

        Path uploadDir = Paths.get(uploadPath, "heroes");
        Files.createDirectories(uploadDir);

        String ext = req.extension() != null ? req.extension() : "png";
        String filename = shop.getId() + "." + ext;
        Path filePath = uploadDir.resolve(filename);
        Files.write(filePath, imageBytes);

        String heroUrl = baseUrl + "/api/shop/heroes/" + filename;
        shop.setHeroImageUrl(heroUrl);
        shopRepo.save(shop);

        return Map.of("heroImageUrl", heroUrl);
    }

    @GetMapping("/heroes/{filename}")
    public ResponseEntity<byte[]> getHero(@PathVariable String filename) throws IOException {
        Path filePath = Paths.get(uploadPath, "heroes", filename);
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