// SPDX-License-Identifier: GPL-3.0-or-later
package com.noxac.pixeldungeon.inn;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.watabou.noosa.ColorBlock;
import com.watabou.noosa.Game;
import com.watabou.noosa.Group;
import com.watabou.noosa.Image;
import com.watabou.noosa.MovieClip;
import com.watabou.noosa.TextureFilm;
import com.watabou.noosa.audio.Sample;

final class DungeonActorView extends Group {
    private static final float STEP_DURATION = BattleRules.MOVE_COOLDOWN_MS / 1000f;
    private static final float PREDICTION_TIMEOUT = 0.55f;
    private static final float STRIDE_GRACE = 0.065f;
    private static final float ATTACK_DURATION = 0.28f;
    private static final float DAMAGE_DURATION = 0.62f;
    private static final float FOOT_OFFSET = 0.89f;

    private final MovieClip sprite;
    private final AvatarSprites outfit;
    private final boolean dummy;
    private final MovieClip.Animation idle;
    private final MovieClip.Animation walk;
    private final MovieClip.Animation attack;
    private final MovieClip.Animation death;
    private final Image shadow;
    private final Group overlay = new Group();
    private final Group speechOverlay = new Group();
    private final RenderedTextBlock name;
    private final RenderedTextBlock healthChange;
    private final ColorBlock healthBack;
    private final ColorBlock healthFill;
    private final Group sweat;
    private final ColorBlock[] sweatPixels;
    private float sweatTime;
    private Group speech;
    private ColorBlock speechBack;
    private ColorBlock speechTail;
    private RenderedTextBlock speechText;
    private long speechUntil;
    private float speechLeft;
    private float speechTop;
    private float speechRight;

    private InnApi.Combatant actor;
    private float mapX;
    private float mapY;
    private float tile = 16;
    private float fromX;
    private float fromY;
    private float toX;
    private float toY;
    private float shownX;
    private float shownY;
    private float stepDuration = STEP_DURATION;
    private float stepTime = STEP_DURATION;
    private float strideIdle = STRIDE_GRACE;
    private boolean predictedMove;
    private float predictionAge;
    private int predictedSequence = -1;
    private float attackTime = ATTACK_DURATION;
    private float attackX;
    private float attackY;
    private float healthChangeTime = DAMAGE_DURATION;
    private boolean hurt;
    private float deadTime;
    private boolean facingLeft;

    DungeonActorView(InnApi.Combatant actor, boolean own, boolean host) {
        this.actor = actor;
        fromX = toX = shownX = actor.x();
        fromY = toY = shownY = actor.y();

        boolean enemy = "enemy".equals(actor.kind());
        dummy = "dummy".equals(actor.kind());
        String baseSprite = dummy ? NativeDungeonScene.TRAINING_SPRITES
            : enemy ? Assets.Sprites.RAT : host ? Assets.Sprites.WARRIOR : Assets.Sprites.ROGUE;
        outfit = "player".equals(actor.kind()) ? AvatarSprites.acquire(actor.appearance(), baseSprite) : null;
        Object texture = outfit == null ? baseSprite : outfit.texture();
        sprite = new MovieClip(texture);
        if (dummy) {
            TextureFilm atlas = new TextureFilm(texture, 16, 16);
            idle = walk = attack = death = animation(1, true, atlas, 0);
        } else {
            TextureFilm atlas = new TextureFilm(texture, enemy ? 16 : AvatarSprites.FRAME_WIDTH, AvatarSprites.FRAME_HEIGHT);
            idle = enemy ? animation(2, true, atlas, 0, 0, 0, 1) : animation(1, true, atlas, 0, 0, 0, 1, 0, 0, 1, 1);
            walk = enemy ? animation(10, true, atlas, 6, 7, 8, 9, 10) : animation(20, true, atlas, 2, 3, 4, 5, 6, 7);
            attack = enemy ? animation(15, false, atlas, 2, 3, 4, 5, 0) : animation(15, false, atlas, 13, 14, 15, 0);
            death = enemy ? animation(10, false, atlas, 11, 12, 13, 14) : animation(20, false, atlas, 8, 9, 10, 11, 12, 11);
        }
        shadow = new Image(Assets.Interfaces.SHADOW);
        shadow.alpha(0.62f);
        add(shadow);
        add(sprite);
        sprite.play(idle);

        int color = enemy ? NativeDungeonScene.ENEMY : own || dummy ? NativeDungeonScene.SELF : NativeDungeonScene.ALLY;
        healthBack = NativeDungeonScene.panel(overlay, 0, 0, 1, 1, 0xE8131715);
        healthFill = NativeDungeonScene.panel(overlay, 0, 0, 1, 1, 0xFF000000 | color);
        String nick = actor.name();
        if (nick.codePointCount(0, nick.length()) > 9) nick = nick.substring(0, nick.offsetByCodePoints(0, 8)) + "…";
        name = NativeDungeonScene.label(overlay, enemy ? "" : nick, 6, color, 0, 0, 65);
        healthChange = NativeDungeonScene.label(overlay, "", 8, NativeDungeonScene.ENEMY, 0, 0, 38);
        sweat = own ? new Group() : null;
        sweatPixels = own ? new ColorBlock[] {
            NativeDungeonScene.panel(sweat, 0, 0, 1, 5, 0xFF204C64),
            NativeDungeonScene.panel(sweat, 0, 0, 3, 2, 0xFF204C64),
            NativeDungeonScene.panel(sweat, 0, 0, 1, 3, 0xFF69C6E3),
            NativeDungeonScene.panel(sweat, 0, 0, 1, 1, 0xFFC5F5FA),
        } : null;
        if (sweat != null) {
            sweat.visible = false;
            overlay.add(sweat);
        }
        if (actor.hp() <= 0) {
            deadTime = 1;
            sprite.frame(death.frames[death.frames.length - 1]);
            sprite.paused = true;
        }
    }

    private static MovieClip.Animation animation(int fps, boolean loop, TextureFilm atlas, Object... frames) {
        return new MovieClip.Animation(fps, loop).frames(atlas, frames);
    }

    void place(float mapX, float mapY, float tile) {
        this.mapX = mapX;
        this.mapY = mapY;
        this.tile = tile;
        layout();
    }

    float depth() { return shownY + FOOT_OFFSET; }

    Group overlay() { return overlay; }
    Group speechOverlay() { return speechOverlay; }

    void waitForEnergy(boolean waiting) {
        if (sweat != null && sweat.visible != waiting) {
            sweatTime = 0;
            sweat.visible = waiting;
        }
    }

    void speak(String message, long deadline, float boardX, float boardY, float boardWidth) {
        if (!"player".equals(actor.kind())) return;
        if (speech == null) {
            speech = new Group();
            speechOverlay.add(speech);
            speechBack = NativeDungeonScene.panel(speech, 0, 0, 1, 1, 0xF12B2D25);
            speechTail = NativeDungeonScene.panel(speech, 0, 0, 4, 2, 0xF12B2D25);
            speechText = PixelScene.renderTextBlock(7, false);
            speechText.maxWidth(97);
            speechText.setHightlighting(false);
            speechText.hardlight(NativeDungeonScene.TEXT);
            speech.add(speechText);
        }
        int length = message.codePointCount(0, message.length());
        String preview = length <= 28 ? message : message.substring(0, message.offsetByCodePoints(0, 27)) + "…";
        speechText.text(NativeDungeonScene.wrapLongChatWords(preview, 14));
        speechText.visible = true;
        speechUntil = deadline;
        speechLeft = boardX + 3;
        speechTop = boardY + 3;
        speechRight = boardX + boardWidth - 3;
        speech.visible = true;
        layoutSpeech();
    }

    void expireSpeech(long now) {
        if (speech != null && now >= speechUntil) speech.visible = false;
    }

    @Override
    public void destroy() {
        overlay.remove();
        overlay.destroy();
        speechOverlay.remove();
        speechOverlay.destroy();
        super.destroy();
        if (outfit != null) outfit.close();
    }

    void previewMove(int dx, int dy, boolean newInput) {
        if (actor.hp() <= 0 || predictedMove || (!newInput && predictedSequence == actor.actionSeq())) return;
        // Simulation still uses the last confirmed tile while this stride is shown.
        predictedMove = true;
        predictionAge = 0;
        predictedSequence = actor.actionSeq();
        strideTo(actor.x() + dx, actor.y() + dy, dx);
    }

    void cancelPreviewMove() {
        if (!predictedMove) return;
        predictedMove = false;
        strideTo(actor.x(), actor.y(), 0);
    }

    private void strideTo(float x, float y, int dx) {
        fromX = shownX;
        fromY = shownY;
        toX = x;
        toY = y;
        float distance = Math.max(Math.abs(toX - fromX), Math.abs(toY - fromY));
        stepDuration = Math.max(0.035f, STEP_DURATION * Math.min(1, distance));
        stepTime = distance > 0.001f ? 0 : stepDuration;
        if (stepTime == 0) strideIdle = 0;
        face(dx);
    }

    void accept(InnApi.Combatant next, InnApi.Combatant target, long now) {
        boolean actionChanged = next.actionSeq() != actor.actionSeq();
        long age = Math.max(0, now - next.actionAt());
        if (next.x() != actor.x() || next.y() != actor.y()) {
            if (!predictedMove || next.x() != toX || next.y() != toY) {
                int span = Math.max(Math.abs(next.x() - actor.x()), Math.abs(next.y() - actor.y()));
                strideTo(next.x(), next.y(), next.x() - actor.x());
                if (span > 2) stepTime = stepDuration;
            }
            predictedMove = false;
        }
        if (actionChanged && "attack".equals(next.lastAction()) && age <= 1_200 && next.hp() > 0) {
            attackTime = 0;
            attackX = target == null ? 0 : target.x() - next.x();
            attackY = target == null ? 0 : target.y() - next.y();
            float distance = Math.max(1, (float) Math.hypot(attackX, attackY));
            attackX /= distance;
            attackY /= distance;
            face(attackX);
            sprite.play(attack, true);
        }
        if (next.hp() != actor.hp()) {
            int change = next.hp() - actor.hp();
            // A dummy immediately resetting to full health is the result of a hit.
            hurt = dummy || change < 0;
            if (!hurt) {
                healthChange.text("+" + change);
                healthChange.hardlight(NativeDungeonScene.ALLY);
            }
            healthChangeTime = 0;
            if (hurt) Sample.INSTANCE.play(Assets.Sounds.HIT, 0.45f);
        }
        if (actor.hp() > 0 && next.hp() <= 0) {
            deadTime = 0;
            attackTime = ATTACK_DURATION;
            predictedMove = false;
            strideTo(next.x(), next.y(), 0);
            sprite.play(death, true);
        }
        actor = next;
    }

    private void face(float deltaX) {
        if (deltaX != 0) facingLeft = deltaX < 0;
    }

    @Override
    public void update() {
        if (predictedMove) {
            predictionAge += Game.elapsed;
            if (predictionAge >= PREDICTION_TIMEOUT) cancelPreviewMove();
        }
        strideIdle = stepTime < stepDuration ? 0 : Math.min(STRIDE_GRACE, strideIdle + Game.elapsed);
        stepTime = Math.min(stepDuration, stepTime + Game.elapsed);
        attackTime = Math.min(ATTACK_DURATION, attackTime + Game.elapsed);
        healthChangeTime = Math.min(DAMAGE_DURATION, healthChangeTime + Game.elapsed);
        if (sweat != null && sweat.visible) sweatTime = (sweatTime + Game.elapsed) % 0.62f;
        if (actor.hp() > 0) {
            if (attackTime >= ATTACK_DURATION) {
                sprite.play(strideIdle < STRIDE_GRACE ? walk : idle);
            }
        } else {
            deadTime += Game.elapsed;
        }
        layout();
        super.update();
    }

    private void layout() {
        float progress = stepTime / stepDuration;
        shownX = fromX + (toX - fromX) * progress;
        shownY = fromY + (toY - fromY) * progress;
        float center = mapX + (shownX + 0.5f) * tile;
        float base = mapY + depth() * tile;
        float scale = tile / 16;
        float lunge = (float) Math.sin(Math.PI * attackTime / ATTACK_DURATION) * tile * 0.24f;

        sprite.scale.set(scale);
        sprite.flipHorizontal = facingLeft;
        sprite.x = center - sprite.width() / 2 + lunge * attackX;
        sprite.y = base - sprite.height() + lunge * attackY;
        sprite.resetColor();
        if (hurt && healthChangeTime < 0.19f) sprite.tint(0xF6A48A, (1 - healthChangeTime / 0.19f) * 0.8f);
        if (actor.hp() <= 0) sprite.alpha(Math.max(0.18f, 1 - Math.max(0, deadTime - 0.31f) * 1.8f));

        shadow.scale.set(scale);
        shadow.x = center - shadow.width() / 2;
        shadow.y = base - shadow.height() / 2;
        shadow.visible = actor.hp() > 0 || deadTime < 0.38f;
        float healthWidth = tile * 0.71f;
        healthBack.x = healthFill.x = center - healthWidth / 2;
        healthBack.y = healthFill.y = base + scale;
        healthBack.size(healthWidth, 2 * scale);
        healthFill.size(healthWidth * Math.max(0, actor.hp()) / Math.max(1, actor.maxHp()), 2 * scale);
        healthBack.visible = healthFill.visible = actor.hp() > 0;

        name.visible = actor.hp() > 0 && !name.text().isBlank();
        if (name.visible) name.setPos(center - name.width() / 2, sprite.y - 7);
        healthChange.visible = !hurt && healthChangeTime < DAMAGE_DURATION;
        if (healthChange.visible) {
            healthChange.setPos(center - healthChange.width() / 2, sprite.y - 7 - 13 * healthChangeTime / DAMAGE_DURATION);
            healthChange.alpha(1 - healthChangeTime / DAMAGE_DURATION);
        }
        if (sweat != null && sweat.visible) layoutSweat(center, base, scale);
        layoutSpeech();
    }

    private void layoutSweat(float center, float base, float scale) {
        float phase = sweatTime / 0.62f;
        float x = center + Math.round(5 + 4 * phase) * scale;
        float y = base + Math.round(-14.5f + 5 * phase) * scale;
        placeSweatPixel(sweatPixels[0], x + scale, y, scale, 5 * scale);
        placeSweatPixel(sweatPixels[1], x, y + 2 * scale, 3 * scale, 2 * scale);
        placeSweatPixel(sweatPixels[2], x + scale, y + scale, scale, 3 * scale);
        placeSweatPixel(sweatPixels[3], x + scale, y + scale, scale, scale);
    }

    private static void placeSweatPixel(ColorBlock pixel, float x, float y, float width, float height) {
        pixel.x = x;
        pixel.y = y;
        pixel.size(width, height);
    }

    private void layoutSpeech() {
        if (speech == null || !speech.visible) return;
        float center = sprite.x + sprite.width() / 2;
        float width = Math.min(speechRight - speechLeft, speechText.width() + 8);
        float height = speechText.height() + 5;
        float x = Math.clamp(center - width / 2, speechLeft, speechRight - width);
        float y = Math.max(speechTop, sprite.y - (name.visible ? 9 : 3) - height);
        speechBack.size(width, height);
        speechBack.x = x;
        speechBack.y = y;
        speechTail.x = Math.clamp(center - 2, x + 2, x + width - 6);
        speechTail.y = y + height;
        speechText.setPos(x + 4, y + 2);
    }
}
