package com.example.stemplekarte.controller;

import com.example.stemplekarte.model.Shop;
import com.example.stemplekarte.repository.ShopRepository;
import com.example.stemplekarte.security.JwtAuthFilter;
import com.example.stemplekarte.wallet.CloudinaryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "Hero", description = "Hero-Image-Upload fuer Laden")
@RestController
@RequestMapping("/api/shop")
public class HeroController {

    private final ShopRepository shopRepo;
    private final CloudinaryService cloudinary;

    public HeroController(ShopRepository shopRepo, CloudinaryService cloudinary) {
        this.shopRepo = shopRepo;
        this.cloudinary = cloudinary;
    }

    public record HeroUploadRequest(String base64, String extension) {}

    @PostMapping("/hero")
    public Map<String, String> uploadHero(@RequestBody HeroUploadRequest req,
                                          Authentication auth) {
        Shop shop = ((JwtAuthFilter.ShopPrincipal) auth.getPrincipal()).shop();

        String url = cloudinary.upload(req.base64(), "hero-" + shop.getId(),
                CloudinaryService.ImageType.HERO);

        shop.setHeroImageUrl(url);
        shopRepo.save(shop);

        return Map.of("heroImageUrl", url);
    }
}