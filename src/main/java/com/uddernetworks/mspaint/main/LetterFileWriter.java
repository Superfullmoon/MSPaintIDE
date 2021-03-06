package com.uddernetworks.mspaint.main;

import com.uddernetworks.newocr.ScannedImage;
import com.uddernetworks.newocr.character.ImageLetter;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

public class LetterFileWriter {

    private ScannedImage scannedImage;
    private File writeFile;
    private BufferedImage image;

    public LetterFileWriter(ScannedImage scannedImage, BufferedImage image, File writeFile) {
        this.scannedImage = scannedImage;
        this.image = image;
        this.writeFile = writeFile;
    }

    public LetterFileWriter(ScannedImage scannedImage, File readFile, File writeFile) throws IOException {
        this.scannedImage = scannedImage;
        this.writeFile = writeFile;
        this.image = ImageIO.read(readFile);
    }

    public void writeToFile() throws IOException {
        AtomicInteger width = new AtomicInteger(this.image.getWidth());
        AtomicInteger height = new AtomicInteger(this.image.getHeight());
        scannedImage.getGrid().values()
                .stream()
                .flatMap(Collection::stream)
                .forEach(imageLetter -> {
                    width.set(Math.max(imageLetter.getX() + imageLetter.getWidth() + 10, width.get()));
                    height.set(Math.max(imageLetter.getY() + imageLetter.getHeight() + 10, height.get()));
                });

        image = new BufferedImage(width.get(), height.get(), BufferedImage.TYPE_INT_ARGB);
        background(Color.WHITE);

        scannedImage.getGrid().values().forEach(line -> {
            line.forEach(imageLetter -> {
                if (imageLetter.getLetter() != ' ') writeLetterToFile(image, imageLetter);
            });
        });

        if (writeFile != null) ImageIO.write(image, "png", writeFile);
    }

    public BufferedImage getImage() {
        return image;
    }

    private void writeLetterToFile(BufferedImage image, ImageLetter imageLetter) {
        imageLetter.getData(Color.class).ifPresent(colorData -> {
            int color = colorData.getRGB();
            boolean[][] data = imageLetter.getValues();
            if (data == null) return;

            for (int y = 0; y < imageLetter.getHeight() + 1; y++) {
                for (int x = 0; x < imageLetter.getWidth(); x++) {
                    if (data[y][x]) image.setRGB(imageLetter.getX() + x, imageLetter.getY() + y, color);
                }
            }
        });
    }

    private void background(Color color) {
        for (int y = 0; y < this.image.getHeight(); y++) {
            for (int x = 0; x < this.image.getWidth(); x++) {
                this.image.setRGB(x, y, color.getRGB());
            }
        }
    }
}
