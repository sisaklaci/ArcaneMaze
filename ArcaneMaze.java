import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.*;
import java.util.List;

/**
 * Arcane Maze: Rift Hunt - FPS labirintus-lovolde
 * by helmetsoft.com
 *
 * Tiszta Java (Swing), sajat szoftveres raycasting renderelovel.
 *
 * Forditas:  javac -encoding UTF-8 ArcaneMaze.java
 * Futtatas:  java ArcaneMaze
 */
public class ArcaneMaze {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                JFrame f = new JFrame("Arcane Maze: Rift Hunt \u2014 by helmetsoft.com");
                f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                GamePanel gp = new GamePanel();
                f.setContentPane(gp);
                f.pack();
                f.setLocationRelativeTo(null);
                f.setVisible(true);
                gp.start();
            }
        });
    }
}

/* ============================= JATEK PANEL ============================= */

class GamePanel extends JPanel implements Runnable {
    static final int RW = 640, RH = 400;           // belso felbontas
    static final int INTRO = 0, MSG = 1, PLAY = 2, DYING = 3, OVER = 4, PAUSE = 5;

    // allapot
    int state = INTRO;
    int dungeon = 1;
    long score = 0, hiScore = 0;
    int lives = 3;
    boolean doubleScore = false, doubleNext = false;
    int phase = 0;                                  // 0: creatures, 1: Riftling, 2: Archmage
    String msgText = "";
    double msgTimer = 0, dieTimer = 0, flashTimer = 0;
    double anim = 0;
    long lastExtraLife = 0;

    Maze maze;
    final Player pl = new Player();
    final List<Monster> monsters = new ArrayList<Monster>();
    final List<Bolt> bolts = new ArrayList<Bolt>();
    final List<Boom> booms = new ArrayList<Boom>();
    final Random rnd = new Random();

    // render
    final BufferedImage frame = new BufferedImage(RW, RH, BufferedImage.TYPE_INT_RGB);
    final int[] fpix = ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();
    final float[] zbuf = new float[RW];
    final int[] ceilRow = new int[RH / 2];
    final int[] floorRow = new int[RH / 2];

    // bemenet
    final boolean[] keys = new boolean[512];
    boolean mouseDown = false, captured = false;
    Robot robot;
    Cursor blankCursor;

    final Sound snd = new Sound();
    Thread loop;
    volatile boolean running = false;

    GamePanel() {
        setPreferredSize(new Dimension(960, 600));
        setBackground(Color.BLACK);
        setFocusable(true);

        for (int y = 0; y < RH / 2; y++) {
            float t = (float) y / (RH / 2);
            ceilRow[y] = rgb(4 + (int) (8 * t), 5 + (int) (10 * t), 14 + (int) (22 * t));
            floorRow[y] = rgb(16 + (int) (26 * t), 17 + (int) (28 * t), 24 + (int) (34 * t));
        }

        try { robot = new Robot(); } catch (Throwable ignored) {}
        blankCursor = Toolkit.getDefaultToolkit().createCustomCursor(
                new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), new Point(0, 0), "blank");

        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                int k = e.getKeyCode();
                if (k < keys.length) keys[k] = true;
                onKey(k);
            }
            @Override public void keyReleased(KeyEvent e) {
                int k = e.getKeyCode();
                if (k < keys.length) keys[k] = false;
            }
        });

        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (state == INTRO) { newGame(); return; }
                if (state == OVER) { state = INTRO; return; }
                if (state == PLAY) {
                    if (!captured) setCaptured(true);
                    mouseDown = true;
                }
            }
            @Override public void mouseReleased(MouseEvent e) { mouseDown = false; }
            @Override public void mouseMoved(MouseEvent e) { look(e); }
            @Override public void mouseDragged(MouseEvent e) { look(e); }
            private void look(MouseEvent e) {
                if (!captured || robot == null || state != PLAY || !isShowing()) return;
                Point p = getLocationOnScreen();
                int cx = p.x + getWidth() / 2, cy = p.y + getHeight() / 2;
                int dx = e.getXOnScreen() - cx;
                if (dx != 0) {
                    pl.dir += dx * 0.0032;
                    robot.mouseMove(cx, cy);
                }
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);

        initDemo();
    }

    void start() {
        requestFocusInWindow();
        if (running) return;
        running = true;
        loop = new Thread(this, "game-loop");
        loop.setDaemon(true);
        loop.start();
    }

    public void run() {
        long t0 = System.nanoTime();
        while (running) {
            long t = System.nanoTime();
            double dt = (t - t0) / 1e9;
            t0 = t;
            if (dt > 0.1) dt = 0.1;
            update(dt);
            renderFrame();
            repaint();
            try { Thread.sleep(14); } catch (InterruptedException e) { return; }
        }
    }

    /* --------------------------- jatek logika --------------------------- */

    void onKey(int k) {
        switch (state) {
            case INTRO:
                if (k == KeyEvent.VK_ENTER || k == KeyEvent.VK_SPACE) newGame();
                break;
            case OVER:
                if (k == KeyEvent.VK_ENTER || k == KeyEvent.VK_SPACE) state = INTRO;
                break;
            case PLAY:
                if (k == KeyEvent.VK_ESCAPE || k == KeyEvent.VK_P) {
                    setCaptured(false);
                    state = PAUSE;
                }
                break;
            case PAUSE:
                if (k == KeyEvent.VK_ESCAPE || k == KeyEvent.VK_P || k == KeyEvent.VK_ENTER)
                    state = PLAY;
                break;
        }
    }

    void setCaptured(boolean b) {
        captured = b;
        setCursor(b ? blankCursor : Cursor.getDefaultCursor());
        if (b && robot != null && isShowing()) {
            Point p = getLocationOnScreen();
            robot.mouseMove(p.x + getWidth() / 2, p.y + getHeight() / 2);
        }
    }

    void initDemo() {
        dungeon = 3;
        buildDungeon();
        state = INTRO;
    }

    void newGame() {
        score = 0;
        lives = 3;
        dungeon = 1;
        doubleScore = false;
        doubleNext = false;
        lastExtraLife = 0;
        buildDungeon();
        showMsg("DUNGEON 1", 2.2);
        snd.play(snd.cAppear);
        setCaptured(true);
    }

    void buildDungeon() {
        maze = new Maze(23, 13, rnd);
        pl.x = maze.w / 2 + 0.5;
        pl.y = maze.h - 2 + 0.5;
        pl.dir = -Math.PI / 2;
        pl.invuln = 2.0;
        pl.shootCd = 0;
        pl.warpCd = 0;
        bolts.clear();
        booms.clear();
        monsters.clear();
        phase = 0;
        int gar = Math.min(dungeon - 1, 4);
        int tho = Math.min(Math.max(dungeon - 3, 0), 3);
        int bur = Math.max(6 - gar - tho, 2) + (dungeon > 5 ? 1 : 0);
        double sf = Math.min(1.0 + 0.06 * (dungeon - 1), 1.8);
        for (int i = 0; i < bur; i++) spawnMonster(0, 1.25 * sf);
        for (int i = 0; i < gar; i++) spawnMonster(1, 1.55 * sf);
        for (int i = 0; i < tho; i++) spawnMonster(2, 1.95 * sf);
    }

    void spawnMonster(int type, double speed) {
        for (int tries = 0; tries < 400; tries++) {
            int cx = 1 + rnd.nextInt(maze.w - 2);
            int cy = 1 + rnd.nextInt(maze.h - 2);
            if (maze.g[cy][cx] != 0) continue;
            if (Math.abs(cx + 0.5 - pl.x) + Math.abs(cy + 0.5 - pl.y) < 7) continue;
            Monster m = new Monster(type, cx + 0.5, cy + 0.5, speed);
            m.shootCd = 2 + rnd.nextDouble() * 3;
            monsters.add(m);
            return;
        }
    }

    void showMsg(String s, double t) {
        msgText = s;
        msgTimer = t;
        state = MSG;
    }

    void addScore(long n) {
        long old = score;
        score += n * (doubleScore ? 2 : 1);
        if (score > hiScore) hiScore = score;
        if (score / 20000 > old / 20000) {
            lives++;
            snd.play(snd.cExtra);
        }
    }

    void update(double dt) {
        anim += dt;
        if (flashTimer > 0) flashTimer -= dt;
        switch (state) {
            case INTRO:
                pl.dir += dt * 0.22;
                updateMonsters(dt, false);
                break;
            case MSG:
                msgTimer -= dt;
                updateBooms(dt);
                if (msgTimer <= 0) state = PLAY;
                break;
            case PLAY:
                updatePlay(dt);
                break;
            case DYING:
                dieTimer -= dt;
                updateBooms(dt);
                if (dieTimer <= 0) afterDeath();
                break;
        }
    }

    void updatePlay(double dt) {
        // forgas
        if (keys[KeyEvent.VK_LEFT]) pl.dir -= 2.6 * dt;
        if (keys[KeyEvent.VK_RIGHT]) pl.dir += 2.6 * dt;

        // mozgas
        double c = Math.cos(pl.dir), s = Math.sin(pl.dir);
        double mx = 0, my = 0;
        if (keys[KeyEvent.VK_W] || keys[KeyEvent.VK_UP]) { mx += c; my += s; }
        if (keys[KeyEvent.VK_S] || keys[KeyEvent.VK_DOWN]) { mx -= c; my -= s; }
        if (keys[KeyEvent.VK_A]) { mx += s; my -= c; }
        if (keys[KeyEvent.VK_D]) { mx -= s; my += c; }
        double ml = Math.sqrt(mx * mx + my * my);
        if (ml > 1e-6) {
            double sp = 3.2;
            mx = mx / ml * sp * dt;
            my = my / ml * sp * dt;
            double nx = pl.x + mx;
            if (passable(nx, pl.y)) pl.x = nx;
            double ny = pl.y + my;
            if (passable(pl.x, ny)) pl.y = ny;
            pl.walk += dt * 5;
        }

        // warp ajtok
        pl.warpCd -= dt;
        int pcx = (int) pl.x, pcy = (int) pl.y;
        if (pl.warpCd <= 0 && pcy == maze.doorY) {
            if (pcx == 0) {
                pl.x = maze.w - 1.4;
                pl.warpCd = 1.2;
                snd.play(snd.cWarp);
            } else if (pcx == maze.w - 1) {
                pl.x = 1.4;
                pl.warpCd = 1.2;
                snd.play(snd.cWarp);
            }
        }

        // loves
        pl.shootCd -= dt;
        pl.invuln -= dt;
        if ((keys[KeyEvent.VK_SPACE] || mouseDown) && pl.shootCd <= 0) {
            Bolt b = new Bolt(pl.x + c * 0.45, pl.y + s * 0.45, c, s, 13, true);
            bolts.add(b);
            pl.shootCd = 0.33;
            pl.lastShot = anim;
            snd.play(snd.cShoot);
        }

        updateMonsters(dt, true);
        updateBolts(dt);
        updateBooms(dt);
        phaseLogic();
    }

    boolean passable(double x, double y) {
        double r = 0.26;
        return !wallAt(x - r, y - r) && !wallAt(x + r, y - r)
                && !wallAt(x - r, y + r) && !wallAt(x + r, y + r);
    }

    boolean wallAt(double x, double y) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        if (ix < 0 || iy < 0 || ix >= maze.w || iy >= maze.h) return true;
        return maze.g[iy][ix] != 0;
    }

    boolean los(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1, dy = y2 - y1;
        double d = Math.sqrt(dx * dx + dy * dy);
        if (d < 1e-6) return true;
        int steps = (int) (d / 0.2);
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            if (wallAt(x1 + dx * t, y1 + dy * t)) return false;
        }
        return true;
    }

    void updateMonsters(double dt, boolean active) {
        for (Iterator<Monster> it = monsters.iterator(); it.hasNext(); ) {
            Monster m = it.next();

            if (m.type == 4) { // varazslo
                m.ttl -= dt;
                m.tpTimer -= dt;
                if (m.ttl <= 0 && active) {
                    it.remove();
                    mageDone(false);
                    continue;
                }
                if (m.tpTimer <= 0) {
                    for (int tries = 0; tries < 200; tries++) {
                        int cx = 1 + rnd.nextInt(maze.w - 2);
                        int cy = 1 + rnd.nextInt(maze.h - 2);
                        if (maze.g[cy][cx] != 0) continue;
                        double d = Math.hypot(cx + 0.5 - pl.x, cy + 0.5 - pl.y);
                        if (d < 2.5) continue;
                        m.x = cx + 0.5; m.y = cy + 0.5;
                        m.cx = cx; m.cy = cy;
                        break;
                    }
                    m.tpTimer = 1.4 + rnd.nextDouble() * 1.2;
                    snd.play(snd.cTeleport);
                    if (active && los(m.x, m.y, pl.x, pl.y)) {
                        double dx = pl.x - m.x, dy = pl.y - m.y;
                        double d = Math.max(1e-6, Math.hypot(dx, dy));
                        bolts.add(new Bolt(m.x, m.y, dx / d, dy / d, 5.5 + 0.25 * dungeon, false));
                        snd.play(snd.cEnemyShoot);
                    }
                }
            } else {
                // cellarol cellara mozgas
                double tx = m.cx + 0.5, ty = m.cy + 0.5;
                double dx = tx - m.x, dy = ty - m.y;
                double d = Math.hypot(dx, dy);
                double step = m.speed * dt;
                if (step >= d) {
                    m.x = tx; m.y = ty;
                    chooseNext(m);
                } else {
                    m.x += dx / d * step;
                    m.y += dy / d * step;
                }

                // temporary cloaking (Glimmerfang, Nightmaw)
                if (m.type == 1 || m.type == 2) {
                    m.blink -= dt;
                    if (m.blink <= 0) {
                        m.vis = !m.vis;
                        m.blink = m.vis ? (m.type == 1 ? 1.8 : 1.1)
                                        : (m.type == 1 ? 2.6 : 3.4);
                    }
                }

                // Riftling escapes through a portal
                if (m.type == 3 && active) {
                    int mcx = (int) m.x, mcy = (int) m.y;
                    if (mcy == maze.doorY && (mcx == 0 || mcx == maze.w - 1)) {
                        it.remove();
                        snd.play(snd.cWarp);
                        riftlingDone(false);
                        continue;
                    }
                }

                // loves
                if (active && m.type != 3) {
                    m.shootCd -= dt;
                    double pd = Math.hypot(pl.x - m.x, pl.y - m.y);
                    if (m.shootCd <= 0 && pd < 9 && los(m.x, m.y, pl.x, pl.y)) {
                        double ddx = pl.x - m.x, ddy = pl.y - m.y;
                        double dl = Math.max(1e-6, pd);
                        bolts.add(new Bolt(m.x, m.y, ddx / dl, ddy / dl, 4.5 + 0.25 * dungeon, false));
                        m.shootCd = 1.8 + rnd.nextDouble() * 2.2 - Math.min(0.9, 0.08 * dungeon);
                        m.vis = true;
                        m.blink = Math.max(m.blink, 0.7);
                        snd.play(snd.cEnemyShoot);
                    }
                }
            }

            // utkozes a jatekossal
            if (active && pl.invuln <= 0
                    && Math.hypot(pl.x - m.x, pl.y - m.y) < 0.48) {
                playerDies();
                return;
            }
        }
    }

    void chooseNext(Monster m) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        List<int[]> open = new ArrayList<int[]>();
        for (int[] d : dirs) {
            int nx = m.cx + d[0], ny = m.cy + d[1];
            if (nx < 0 || ny < 0 || nx >= maze.w || ny >= maze.h) continue;
            if (maze.g[ny][nx] != 0) continue;
            boolean isDoor = (nx == 0 || nx == maze.w - 1);
            if (isDoor && m.type != 3) continue;      // only the Riftling uses portals
            open.add(new int[]{nx, ny});
        }
        if (open.isEmpty()) return;
        // ne forduljon vissza, ha van mas ut
        if (open.size() > 1) {
            for (Iterator<int[]> it = open.iterator(); it.hasNext(); ) {
                int[] o = it.next();
                if (o[0] == m.px && o[1] == m.py && open.size() > 1) it.remove();
            }
        }
        int[] pick;
        double chase = Math.min(0.85, 0.5 + 0.045 * dungeon);
        if (m.type == 3) {
            // Riftling seeks the nearest portal
            double bl = Math.abs(m.cy - maze.doorY) + m.cx;
            double br = Math.abs(m.cy - maze.doorY) + (maze.w - 1 - m.cx);
            final int gx = bl <= br ? 0 : maze.w - 1, gy = maze.doorY;
            pick = best(open, gx, gy);
            if (rnd.nextDouble() > 0.8) pick = open.get(rnd.nextInt(open.size()));
        } else if (rnd.nextDouble() < chase) {
            pick = best(open, (int) pl.x, (int) pl.y);
        } else {
            pick = open.get(rnd.nextInt(open.size()));
        }
        m.px = m.cx; m.py = m.cy;
        m.cx = pick[0]; m.cy = pick[1];
    }

    int[] best(List<int[]> open, int gx, int gy) {
        int[] bestC = open.get(0);
        int bestD = Integer.MAX_VALUE;
        for (int[] o : open) {
            int d = Math.abs(o[0] - gx) + Math.abs(o[1] - gy);
            if (d < bestD) { bestD = d; bestC = o; }
        }
        return bestC;
    }

    void updateBolts(double dt) {
        for (Iterator<Bolt> it = bolts.iterator(); it.hasNext(); ) {
            Bolt b = it.next();
            b.x += b.dx * b.speed * dt;
            b.y += b.dy * b.speed * dt;
            if (wallAt(b.x, b.y)) {
                booms.add(new Boom(b.x - b.dx * 0.2, b.y - b.dy * 0.2, 0.35, 0.28));
                it.remove();
                continue;
            }
            if (b.fromPlayer) {
                Monster hit = null;
                for (Monster m : monsters) {
                    if (Math.hypot(m.x - b.x, m.y - b.y) < 0.42) { hit = m; break; }
                }
                if (hit != null) {
                    it.remove();
                    killMonster(hit);
                }
            } else if (pl.invuln <= 0 && Math.hypot(pl.x - b.x, pl.y - b.y) < 0.34) {
                it.remove();
                playerDies();
                return;
            }
        }
    }

    void killMonster(Monster m) {
        monsters.remove(m);
        booms.add(new Boom(m.x, m.y, 0.55, 0.85));
        snd.play(snd.cBoom);
        switch (m.type) {
            case 0: addScore(100); break;
            case 1: addScore(200); break;
            case 2: addScore(500); break;
            case 3: addScore(1000); riftlingDone(true); break;
            case 4: addScore(2500); mageDone(true); break;
        }
    }

    void updateBooms(double dt) {
        for (Iterator<Boom> it = booms.iterator(); it.hasNext(); ) {
            Boom bm = it.next();
            bm.age += dt;
            if (bm.age >= bm.ttl) it.remove();
        }
    }

    void phaseLogic() {
        if (phase == 0 && monsters.isEmpty()) {
            phase = 1;
            spawnMonster(3, 2.6 + 0.08 * dungeon);
            if (!monsters.isEmpty()) {
                showMsg("RIFTLING!", 1.6);
                snd.play(snd.cAppear);
            } else {
                endDungeon();
            }
        }
    }

    void riftlingDone(boolean killed) {
        if (killed) doubleNext = true;
        if (dungeon >= 2 && rnd.nextDouble() < 0.55) {
            phase = 2;
            Monster w = new Monster(4, 0, 0, 0);
            w.ttl = 18;
            w.tpTimer = 0;
            monsters.add(w);
            showMsg("A VAR\u00c1ZSL\u00d3!", 1.8);
            snd.play(snd.cMage);
        } else {
            endDungeon();
        }
    }

    void mageDone(boolean killed) {
        endDungeon();
    }

    void endDungeon() {
        dungeon++;
        doubleScore = doubleNext;
        doubleNext = false;
        buildDungeon();
        showMsg(doubleScore ? "DUNGEON " + dungeon + " \u2014 DUPLA PONT!" : "DUNGEON " + dungeon, 2.2);
        snd.play(snd.cAppear);
    }

    void playerDies() {
        booms.add(new Boom(pl.x + Math.cos(pl.dir) * 0.4, pl.y + Math.sin(pl.dir) * 0.4, 0.6, 0.9));
        snd.play(snd.cDie);
        flashTimer = 0.5;
        state = DYING;
        dieTimer = 1.6;
    }

    void afterDeath() {
        lives--;
        if (lives <= 0) {
            if (score > hiScore) hiScore = score;
            setCaptured(false);
            state = OVER;
            return;
        }
        pl.x = maze.w / 2 + 0.5;
        pl.y = maze.h - 2 + 0.5;
        pl.dir = -Math.PI / 2;
        pl.invuln = 2.5;
        for (Iterator<Bolt> it = bolts.iterator(); it.hasNext(); )
            if (!it.next().fromPlayer) it.remove();
        showMsg("K\u00c9SZ\u00dcLJ!", 1.4);
    }

    /* --------------------------- renderer --------------------------- */

    void renderFrame() {
        // plafon / padlo
        for (int y = 0; y < RH / 2; y++)
            Arrays.fill(fpix, y * RW, y * RW + RW, ceilRow[y]);
        for (int y = RH / 2; y < RH; y++)
            Arrays.fill(fpix, y * RW, y * RW + RW, floorRow[y - RH / 2]);

        double dirX = Math.cos(pl.dir), dirY = Math.sin(pl.dir);
        double planeX = -dirY * 0.66, planeY = dirX * 0.66;

        // falak (DDA raycast)
        for (int x = 0; x < RW; x++) {
            double camX = 2.0 * x / RW - 1;
            double rdx = dirX + planeX * camX;
            double rdy = dirY + planeY * camX;
            int mapX = (int) pl.x, mapY = (int) pl.y;
            double ddx = rdx == 0 ? 1e30 : Math.abs(1 / rdx);
            double ddy = rdy == 0 ? 1e30 : Math.abs(1 / rdy);
            double sdx, sdy;
            int stepX, stepY;
            if (rdx < 0) { stepX = -1; sdx = (pl.x - mapX) * ddx; }
            else { stepX = 1; sdx = (mapX + 1 - pl.x) * ddx; }
            if (rdy < 0) { stepY = -1; sdy = (pl.y - mapY) * ddy; }
            else { stepY = 1; sdy = (mapY + 1 - pl.y) * ddy; }
            int side = 0;
            boolean outside = false;
            for (int i = 0; i < 128; i++) {
                if (sdx < sdy) { sdx += ddx; mapX += stepX; side = 0; }
                else { sdy += ddy; mapY += stepY; side = 1; }
                if (mapX < 0 || mapY < 0 || mapX >= maze.w || mapY >= maze.h) {
                    outside = true;
                    break;
                }
                if (maze.g[mapY][mapX] != 0) break;
            }
            double perp = side == 0 ? sdx - ddx : sdy - ddy;
            if (perp < 0.03) perp = 0.03;
            zbuf[x] = (float) perp;

            int lineH = (int) (RH / perp);
            int ds = RH / 2 - lineH / 2;
            int de = ds + lineH;
            int y0 = Math.max(0, ds), y1 = Math.min(RH, de);

            double wallX = side == 0 ? pl.y + perp * rdy : pl.x + perp * rdx;
            wallX -= Math.floor(wallX);
            int tx = (int) (wallX * 64) & 63;

            float f = fog(perp);
            if (side == 1) f *= 0.72f;
            int fi = (int) (f * 256);
            int[] t = outside ? Art.doorTex : Art.wallTex;

            for (int y = y0; y < y1; y++) {
                int ty = ((y - ds) * 64 / lineH) & 63;
                int cc = t[ty * 64 + tx];
                int r = (((cc >> 16) & 255) * fi) >> 8;
                int g = (((cc >> 8) & 255) * fi) >> 8;
                int b = ((cc & 255) * fi) >> 8;
                fpix[y * RW + x] = (r << 16) | (g << 8) | b;
            }
        }

        // sprite-ok melyseg szerint
        List<double[]> list = new ArrayList<double[]>(); // [dist, kind, index]
        for (int i = 0; i < monsters.size(); i++) {
            Monster m = monsters.get(i);
            double d = Math.hypot(m.x - pl.x, m.y - pl.y);
            if ((m.type == 1 || m.type == 2) && !m.vis && d > 2.3 && state != INTRO) continue;
            list.add(new double[]{d, 0, i});
        }
        for (int i = 0; i < bolts.size(); i++) {
            Bolt b = bolts.get(i);
            list.add(new double[]{Math.hypot(b.x - pl.x, b.y - pl.y), 1, i});
        }
        for (int i = 0; i < booms.size(); i++) {
            Boom bm = booms.get(i);
            list.add(new double[]{Math.hypot(bm.x - pl.x, bm.y - pl.y), 2, i});
        }
        Collections.sort(list, new Comparator<double[]>() {
            public int compare(double[] a, double[] b) { return Double.compare(b[0], a[0]); }
        });
        int frameIdx = ((int) (anim * 6)) & 1;
        for (double[] e : list) {
            int kind = (int) e[1], idx = (int) e[2];
            if (kind == 0) {
                if (idx >= monsters.size()) continue;
                Monster m = monsters.get(idx);
                BufferedImage img = Art.monster(m.type, frameIdx);
                drawSprite(img, m.x, m.y, dirX, dirY, planeX, planeY,
                        m.type == 4 ? 0.85 : 0.62, true, 1f);
            } else if (kind == 1) {
                if (idx >= bolts.size()) continue;
                Bolt b = bolts.get(idx);
                drawSprite(b.fromPlayer ? Art.boltP : Art.boltE, b.x, b.y,
                        dirX, dirY, planeX, planeY, 0.16, false, 1f);
            } else {
                if (idx >= booms.size()) continue;
                Boom bm = booms.get(idx);
                int bf = Math.min(Art.boomFr.length - 1,
                        (int) (bm.age / bm.ttl * Art.boomFr.length));
                drawSprite(Art.boomFr[bf], bm.x, bm.y, dirX, dirY, planeX, planeY,
                        bm.size * (0.4 + 0.8 * bm.age / bm.ttl), false, 1f);
            }
        }

        drawWeapon();
    }

    void drawSprite(BufferedImage img, double ex, double ey,
                    double dirX, double dirY, double planeX, double planeY,
                    double hScale, boolean onFloor, float alphaMul) {
        double relX = ex - pl.x, relY = ey - pl.y;
        double invDet = 1.0 / (planeX * dirY - dirX * planeY);
        double transX = invDet * (dirY * relX - dirX * relY);
        double transY = invDet * (-planeY * relX + planeX * relY);
        if (transY < 0.1) return;
        int sxc = (int) ((RW / 2.0) * (1 + transX / transY));
        double wallH = RH / transY;
        int sh = (int) (wallH * hScale);
        if (sh < 2) return;
        int sw = sh;
        int top;
        if (onFloor) top = (int) (RH / 2 + wallH / 2) - sh;
        else top = RH / 2 - sh / 2;
        int xs = sxc - sw / 2;

        int iw = img.getWidth(), ih = img.getHeight();
        int[] ip = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        float f = fog(transY);
        int fi = (int) (f * 256 * alphaMul);

        int xa = Math.max(0, xs), xb = Math.min(RW, xs + sw);
        int ya = Math.max(0, top), yb = Math.min(RH, top + sh);
        for (int x = xa; x < xb; x++) {
            if (transY >= zbuf[x]) continue;
            int tx = (x - xs) * iw / sw;
            for (int y = ya; y < yb; y++) {
                int ty = (y - top) * ih / sh;
                int argb = ip[ty * iw + tx];
                if ((argb >>> 24) < 60) continue;
                int r = (((argb >> 16) & 255) * fi) >> 8;
                int g = (((argb >> 8) & 255) * fi) >> 8;
                int b = ((argb & 255) * fi) >> 8;
                fpix[y * RW + x] = (r << 16) | (g << 8) | b;
            }
        }
    }

    void drawWeapon() {
        Graphics2D g = frame.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double bob = Math.sin(pl.walk * 2) * 5;
        double bob2 = Math.abs(Math.cos(pl.walk * 2)) * 4;
        boolean flash = (anim - pl.lastShot) < 0.09;
        int tipX = (int) (RW / 2 + 44 + bob);
        int tipY = (int) (RH - 118 + bob2 + (pl.shootCd > 0.22 ? 10 : 0));

        // varazsbot
        g.setStroke(new BasicStroke(10, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(72, 48, 26));
        g.drawLine(RW / 2 + 150, RH + 14, tipX, tipY);
        g.setStroke(new BasicStroke(6, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(110, 76, 40));
        g.drawLine(RW / 2 + 148, RH + 10, tipX, tipY);

        // izzo gomb a vegen
        int orb = flash ? 22 : 14;
        g.setPaint(new RadialGradientPaint(new Point(tipX, tipY), orb,
                new float[]{0f, 0.55f, 1f},
                new Color[]{Color.WHITE, new Color(80, 230, 255),
                        new Color(80, 230, 255, 0)}));
        g.fillOval(tipX - orb, tipY - orb, orb * 2, orb * 2);

        if (flash) {
            g.setColor(new Color(200, 255, 255, 190));
            g.setStroke(new BasicStroke(2.4f));
            for (int i = 0; i < 7; i++) {
                double a = i * Math.PI * 2 / 7 + anim * 10;
                g.drawLine(tipX, tipY,
                        tipX + (int) (Math.cos(a) * 30), tipY + (int) (Math.sin(a) * 30));
            }
        }
        g.dispose();
    }

    static float fog(double d) {
        double f = 1.0 / (1.0 + d * d * 0.03 + d * 0.06);
        return (float) Math.min(1.0, f * 1.18);
    }

    static int rgb(int r, int g, int b) { return (r << 16) | (g << 8) | b; }

    /* --------------------------- HUD / kepernyok --------------------------- */

    @Override protected void paintComponent(Graphics gr) {
        super.paintComponent(gr);
        int W = getWidth(), H = getHeight();
        gr.drawImage(frame, 0, 0, W, H, null);
        Graphics2D g = (Graphics2D) gr;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        switch (state) {
            case INTRO: drawIntro(g, W, H); break;
            case OVER: drawHud(g, W, H); drawOver(g, W, H); break;
            case PAUSE:
                drawHud(g, W, H);
                big(g, W, H / 2, "SZ\u00dcNET", new Color(120, 220, 255), 44);
                small(g, W, H / 2 + 36, "P / ESC \u2014 folytat\u00e1s", new Color(200, 210, 230));
                break;
            case MSG:
                drawHud(g, W, H);
                big(g, W, H / 2 - 20, msgText, new Color(255, 230, 90), 46);
                break;
            case DYING: {
                drawHud(g, W, H);
                int a = (int) (Math.min(1, dieTimer / 1.6) * 150);
                g.setColor(new Color(255, 30, 20, Math.max(0, a)));
                g.fillRect(0, 0, W, H);
                break;
            }
            case PLAY:
                drawHud(g, W, H);
                break;
        }
    }

    void drawHud(Graphics2D g, int W, int H) {
        // celkereszt
        g.setColor(new Color(130, 240, 255, 200));
        g.setStroke(new BasicStroke(2f));
        g.drawLine(W / 2 - 10, H / 2, W / 2 - 3, H / 2);
        g.drawLine(W / 2 + 3, H / 2, W / 2 + 10, H / 2);
        g.drawLine(W / 2, H / 2 - 10, W / 2, H / 2 - 3);
        g.drawLine(W / 2, H / 2 + 3, W / 2, H / 2 + 10);

        // also sav
        g.setColor(new Color(0, 0, 0, 130));
        g.fillRect(0, H - 34, W, 34);
        g.setFont(new Font("Consolas", Font.BOLD, 20));
        g.setColor(new Color(255, 225, 80));
        g.drawString(String.format("PONT %07d", score), 14, H - 10);
        g.setColor(new Color(120, 220, 255));
        String ds = "DUNGEON " + dungeon + (doubleScore ? "  \u00d72" : "");
        g.drawString(ds, W / 2 - g.getFontMetrics().stringWidth(ds) / 2, H - 10);
        // eletek
        g.setColor(new Color(90, 255, 120));
        for (int i = 0; i < Math.min(lives, 8); i++) {
            int lx = W - 24 - i * 22, ly = H - 24;
            Polygon p = new Polygon(
                    new int[]{lx, lx + 7, lx + 14},
                    new int[]{ly + 14, ly, ly + 14}, 3);
            g.fillPolygon(p);
        }

        // serthetetlenseg jelzes
        if (pl.invuln > 0 && state == PLAY && ((int) (anim * 8)) % 2 == 0) {
            g.setColor(new Color(130, 240, 255, 90));
            g.setStroke(new BasicStroke(3f));
            g.drawRect(4, 4, W - 8, H - 42);
        }

        // radar
        int cs = 5;
        int rw = maze.w * cs, rh = maze.h * cs;
        int rx = W - rw - 12, ry = 12;
        g.setColor(new Color(0, 0, 0, 160));
        g.fillRect(rx - 4, ry - 4, rw + 8, rh + 8);
        g.setColor(new Color(30, 60, 150));
        for (int y = 0; y < maze.h; y++)
            for (int x = 0; x < maze.w; x++)
                if (maze.g[y][x] != 0) g.fillRect(rx + x * cs, ry + y * cs, cs, cs);
        g.setColor(new Color(255, 70, 220));
        g.fillRect(rx, ry + maze.doorY * cs, cs, cs);
        g.fillRect(rx + (maze.w - 1) * cs, ry + maze.doorY * cs, cs, cs);
        boolean blink = ((int) (anim * 5)) % 2 == 0;
        for (Monster m : monsters) {
            Color c;
            switch (m.type) {
                case 0: c = new Color(80, 140, 255); break;
                case 1: c = new Color(255, 210, 60); break;
                case 2: c = new Color(255, 80, 60); break;
                case 3: c = blink ? new Color(255, 80, 230) : new Color(180, 40, 160); break;
                default: c = blink ? new Color(90, 240, 255) : new Color(40, 150, 170); break;
            }
            g.setColor(c);
            g.fillOval(rx + (int) (m.x * cs) - 2, ry + (int) (m.y * cs) - 2, 5, 5);
        }
        // jatekos nyil
        g.setColor(Color.WHITE);
        int pxr = rx + (int) (pl.x * cs), pyr = ry + (int) (pl.y * cs);
        int ax = (int) (Math.cos(pl.dir) * 5), ay = (int) (Math.sin(pl.dir) * 5);
        g.setStroke(new BasicStroke(2f));
        g.drawLine(pxr, pyr, pxr + ax, pyr + ay);
        g.fillOval(pxr - 2, pyr - 2, 5, 5);

        // vizjel
        g.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        g.setColor(new Color(255, 255, 255, 45));
        g.drawString("helmetsoft.com", 10, 18);
    }

    void drawIntro(Graphics2D g, int W, int H) {
        g.setColor(new Color(0, 0, 10, 150));
        g.fillRect(0, 0, W, H);

        g.setFont(new Font("Segoe UI", Font.BOLD, 64));
        String t = "ARCANE MAZE";
        int tw = g.getFontMetrics().stringWidth(t);
        g.setColor(new Color(0, 0, 0, 160));
        g.drawString(t, (W - tw) / 2 + 4, 120 + 4);
        g.setPaint(new GradientPaint(0, 70, new Color(140, 230, 255), 0, 130, new Color(250, 250, 255)));
        g.drawString(t, (W - tw) / 2, 120);

        g.setFont(new Font("Segoe UI", Font.BOLD, 30));
        g.setColor(new Color(255, 225, 80));
        String t2 = "3D \u2022 FPS";
        g.drawString(t2, (W - g.getFontMetrics().stringWidth(t2)) / 2, 160);

        g.setFont(new Font("Segoe UI", Font.ITALIC, 20));
        g.setColor(new Color(90, 210, 255));
        String t3 = "by helmetsoft.com";
        g.drawString(t3, (W - g.getFontMetrics().stringWidth(t3)) / 2, 192);

        // szornyek es pontszamok
        int bx = W / 2 - 290, by = 240;
        String[] names = {"GLOOMLING", "GLIMMERFANG", "NIGHTMAW", "RIFTLING", "ARCHMAGE"};
        String[] pts = {"100", "200", "500", "1000", "2500"};
        g.setFont(new Font("Consolas", Font.BOLD, 15));
        for (int i = 0; i < 5; i++) {
            BufferedImage im = Art.monster(i, ((int) (anim * 4) + i) & 1);
            int ix = bx + i * 120;
            g.drawImage(im, ix, by, 64, 64, null);
            g.setColor(new Color(200, 215, 240));
            g.drawString(names[i], ix + 32 - g.getFontMetrics().stringWidth(names[i]) / 2, by + 82);
            g.setColor(new Color(255, 225, 80));
            g.drawString(pts[i], ix + 32 - g.getFontMetrics().stringWidth(pts[i]) / 2, by + 100);
        }

        g.setFont(new Font("Consolas", Font.PLAIN, 17));
        g.setColor(new Color(185, 200, 225));
        String[] help = {
                "WASD \u2014 mozg\u00e1s      eg\u00e9r / \u2190 \u2192 \u2014 forg\u00e1s",
                "kattint\u00e1s / SPACE \u2014 l\u00f6v\u00e9s      P / ESC \u2014 sz\u00fcnet",
                "A s\u00e1rga \u00e9s v\u00f6r\u00f6s sz\u00f6rnyek l\u00e1thatatlann\u00e1 v\u00e1lnak \u2014 figyeld a radart!",
                "Defeat the Riftling for a double-score dungeon!"
        };
        for (int i = 0; i < help.length; i++) {
            String s = help[i];
            g.drawString(s, (W - g.getFontMetrics().stringWidth(s)) / 2, 390 + i * 26);
        }

        if (((int) (anim * 2)) % 2 == 0) {
            g.setFont(new Font("Segoe UI", Font.BOLD, 26));
            g.setColor(new Color(120, 255, 160));
            String s = "NYOMJ ENTERT A KEZD\u00c9SHEZ";
            g.drawString(s, (W - g.getFontMetrics().stringWidth(s)) / 2, H - 60);
        }
        g.setFont(new Font("Consolas", Font.PLAIN, 15));
        g.setColor(new Color(150, 165, 190));
        String hs = String.format("CS\u00daCS: %07d", hiScore);
        g.drawString(hs, (W - g.getFontMetrics().stringWidth(hs)) / 2, H - 26);
    }

    void drawOver(Graphics2D g, int W, int H) {
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRect(0, 0, W, H);
        big(g, W, H / 2 - 50, "GAME OVER", new Color(255, 80, 60), 60);
        g.setFont(new Font("Consolas", Font.BOLD, 26));
        g.setColor(new Color(255, 225, 80));
        String s = String.format("PONTSZ\u00c1M: %07d", score);
        g.drawString(s, (W - g.getFontMetrics().stringWidth(s)) / 2, H / 2 + 10);
        g.setColor(new Color(150, 220, 255));
        String s2 = String.format("CS\u00daCS: %07d", hiScore);
        g.drawString(s2, (W - g.getFontMetrics().stringWidth(s2)) / 2, H / 2 + 46);
        if (((int) (anim * 2)) % 2 == 0)
            small(g, W, H / 2 + 100, "ENTER \u2014 \u00fajra", new Color(120, 255, 160));
    }

    void big(Graphics2D g, int W, int y, String s, Color c, int size) {
        g.setFont(new Font("Segoe UI", Font.BOLD, size));
        int w = g.getFontMetrics().stringWidth(s);
        g.setColor(new Color(0, 0, 0, 180));
        g.drawString(s, (W - w) / 2 + 3, y + 3);
        g.setColor(c);
        g.drawString(s, (W - w) / 2, y);
    }

    void small(Graphics2D g, int W, int y, String s, Color c) {
        g.setFont(new Font("Segoe UI", Font.PLAIN, 20));
        g.setColor(c);
        g.drawString(s, (W - g.getFontMetrics().stringWidth(s)) / 2, y);
    }
}

/* ============================= ADATOSZTALYOK ============================= */

class Player {
    double x, y, dir;
    double invuln, shootCd, warpCd, walk, lastShot = -10;
}

class Monster {
    int type;              // 0 Gloomling, 1 Glimmerfang, 2 Nightmaw, 3 Riftling, 4 Archmage
    double x, y, speed;
    int cx, cy, px = -1, py = -1;
    double shootCd, blink = 1.5;
    boolean vis = true;
    double tpTimer, ttl;   // varazslo

    Monster(int type, double x, double y, double speed) {
        this.type = type;
        this.x = x; this.y = y;
        this.speed = speed;
        this.cx = (int) x;
        this.cy = (int) y;
    }
}

class Bolt {
    double x, y, dx, dy, speed;
    boolean fromPlayer;
    Bolt(double x, double y, double dx, double dy, double speed, boolean fromPlayer) {
        this.x = x; this.y = y; this.dx = dx; this.dy = dy;
        this.speed = speed; this.fromPlayer = fromPlayer;
    }
}

class Boom {
    double x, y, ttl, size, age;
    Boom(double x, double y, double ttl, double size) {
        this.x = x; this.y = y; this.ttl = ttl; this.size = size;
    }
}

/* ============================= LABIRINTUS ============================= */

class Maze {
    final int w, h;
    final int[][] g;
    final int doorY;

    Maze(int w, int h, Random r) {
        this.w = w;
        this.h = h;
        g = new int[h][w];
        for (int[] row : g) Arrays.fill(row, 1);

        // melysegi bejaras (recursive backtracker)
        Deque<int[]> stack = new ArrayDeque<int[]>();
        g[1][1] = 0;
        stack.push(new int[]{1, 1});
        int[][] dirs = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}};
        while (!stack.isEmpty()) {
            int[] c = stack.peek();
            List<int[]> cand = new ArrayList<int[]>();
            for (int[] d : dirs) {
                int nx = c[0] + d[0], ny = c[1] + d[1];
                if (nx > 0 && ny > 0 && nx < w - 1 && ny < h - 1 && g[ny][nx] == 1)
                    cand.add(new int[]{nx, ny});
            }
            if (cand.isEmpty()) { stack.pop(); continue; }
            int[] n = cand.get(r.nextInt(cand.size()));
            g[(c[1] + n[1]) / 2][(c[0] + n[0]) / 2] = 0;
            g[n[1]][n[0]] = 0;
            stack.push(n);
        }

        // hurkok nyitasa (WoW-szeru nyitott arena)
        for (int y = 1; y < h - 1; y++)
            for (int x = 1; x < w - 1; x++)
                if (g[y][x] == 1 && r.nextDouble() < 0.22) {
                    boolean horiz = g[y][x - 1] == 0 && g[y][x + 1] == 0;
                    boolean vert = g[y - 1][x] == 0 && g[y + 1][x] == 0;
                    if (horiz || vert) g[y][x] = 0;
                }

        // warp ajtok kozepen
        doorY = (h / 2) | 1;
        g[doorY][0] = 0;
        g[doorY][1] = 0;
        g[doorY][w - 1] = 0;
        g[doorY][w - 2] = 0;

        // jatekos spawn terulet
        g[h - 2][w / 2] = 0;
        g[h - 2][w / 2 - 1] = 0;
        g[h - 2][w / 2 + 1] = 0;
        g[h - 3][w / 2] = 0;
    }
}

/* ============================= GRAFIKA ============================= */

class Art {
    static final int[] wallTex = new int[64 * 64];
    static final int[] doorTex = new int[64 * 64];
    static final BufferedImage[][] mon = new BufferedImage[5][2];
    static final BufferedImage boltP, boltE;
    static final BufferedImage[] boomFr = new BufferedImage[5];

    static BufferedImage monster(int type, int frame) { return mon[type][frame & 1]; }

    static {
        // fal: sotetkek neon racsokkal
        Random r = new Random(7);
        for (int y = 0; y < 64; y++)
            for (int x = 0; x < 64; x++) {
                int base = 14 + r.nextInt(7);
                int rr = base / 3, gg = base / 2, bb = base + 36;
                boolean line = (x % 16 == 0) || (y % 16 == 0);
                boolean edge = x == 0 || x == 63 || y == 0 || y == 63;
                if (edge) { rr = 16; gg = 60; bb = 150; }
                else if (line) { rr = 12; gg = 44; bb = 120; }
                wallTex[y * 64 + x] = (rr << 16) | (gg << 8) | bb;
            }
        // warp ajto: lila derengés
        for (int y = 0; y < 64; y++)
            for (int x = 0; x < 64; x++) {
                int v = (int) (40 + 36 * Math.sin(x * 0.4) * Math.sin(y * 0.33));
                int rr = 60 + v, gg = 8, bb = 70 + v;
                if (x % 8 == 0) { rr += 50; bb += 60; }
                doorTex[y * 64 + x] = (Math.min(255, rr) << 16) | (gg << 8) | Math.min(255, bb);
            }

        mon[0][0] = wolf(new Color(70, 120, 255), new Color(30, 60, 170), 0);
        mon[0][1] = wolf(new Color(70, 120, 255), new Color(30, 60, 170), 1);
        mon[1][0] = wolf(new Color(255, 205, 50), new Color(190, 120, 20), 0);
        mon[1][1] = wolf(new Color(255, 205, 50), new Color(190, 120, 20), 1);
        mon[2][0] = wolf(new Color(255, 70, 50), new Color(160, 20, 20), 0);
        mon[2][1] = wolf(new Color(255, 70, 50), new Color(160, 20, 20), 1);
        mon[3][0] = riftling(0);
        mon[3][1] = riftling(1);
        mon[4][0] = archmage(0);
        mon[4][1] = archmage(1);

        boltP = bolt(new Color(110, 240, 255), new Color(230, 255, 255));
        boltE = bolt(new Color(255, 90, 40), new Color(255, 230, 160));

        for (int i = 0; i < boomFr.length; i++) boomFr[i] = boom(i, boomFr.length);
    }

    private static BufferedImage img64() {
        return new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
    }

    private static Graphics2D gfx(BufferedImage im) {
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        return g;
    }

    /** Original wolf-like dungeon creatures. */
    private static BufferedImage wolf(Color body, Color dark, int frame) {
        BufferedImage im = img64();
        Graphics2D g = gfx(im);
        int lo = frame == 0 ? 0 : 4;

        // labak
        g.setColor(dark);
        g.fillRect(14 + lo, 46, 7, 16);
        g.fillRect(28 - lo / 2, 48, 7, 16);
        g.fillRect(40 + lo / 2, 48, 7, 16);
        g.fillRect(50 - lo, 46, 7, 16);
        // farok
        Polygon tail = new Polygon(new int[]{2, 14, 12}, new int[]{24, 32, 42}, 3);
        g.setColor(dark);
        g.fillPolygon(tail);
        // test
        g.setColor(body);
        g.fillRoundRect(8, 28, 44, 22, 12, 12);
        // tuskek a haton
        g.setColor(dark);
        for (int i = 0; i < 4; i++) {
            int sx = 14 + i * 9;
            g.fillPolygon(new Polygon(
                    new int[]{sx, sx + 4, sx + 8},
                    new int[]{30, 20 - (i % 2) * 3, 30}, 3));
        }
        // fej
        g.setColor(body);
        g.fillOval(40, 12, 22, 20);
        // allkapocs (nyitva)
        Polygon jaw = new Polygon(new int[]{52, 64, 56}, new int[]{28, 26 + frame * 4, 36}, 3);
        g.setColor(dark);
        g.fillPolygon(jaw);
        // fogak
        g.setColor(Color.WHITE);
        g.fillPolygon(new Polygon(new int[]{54, 57, 56}, new int[]{28, 28, 33}, 3));
        g.fillPolygon(new Polygon(new int[]{58, 61, 60}, new int[]{27, 27, 32}, 3));
        // szem
        g.setColor(new Color(255, 255, 120));
        g.fillOval(48, 17, 7, 7);
        g.setColor(Color.RED);
        g.fillOval(50, 19, 3, 3);
        // ful
        g.setColor(dark);
        g.fillPolygon(new Polygon(new int[]{42, 46, 48}, new int[]{14, 4, 14}, 3));
        g.dispose();
        return im;
    }

    /** Riftling: a winged creature that hunts for an exit portal. */
    private static BufferedImage riftling(int frame) {
        BufferedImage im = img64();
        Graphics2D g = gfx(im);
        int wf = frame == 0 ? 0 : 10;
        // szarnyak
        g.setColor(new Color(230, 120, 255, 170));
        g.fillOval(2, 14 + wf, 26, 30 - wf);
        g.fillOval(36, 14 + wf, 26, 30 - wf);
        g.setColor(new Color(255, 200, 255, 120));
        g.fillOval(6, 18 + wf, 18, 20 - wf / 2);
        g.fillOval(40, 18 + wf, 18, 20 - wf / 2);
        // test
        g.setColor(new Color(150, 40, 170));
        g.fillOval(24, 10, 16, 44);
        g.setColor(new Color(200, 70, 220));
        g.fillOval(26, 14, 12, 14);
        // szemek
        g.setColor(new Color(255, 60, 60));
        g.fillOval(26, 16, 5, 6);
        g.fillOval(33, 16, 5, 6);
        // csapok
        g.setColor(new Color(255, 200, 255));
        g.setStroke(new BasicStroke(2f));
        g.drawLine(28, 12, 22, 2);
        g.drawLine(36, 12, 42, 2);
        g.dispose();
        return im;
    }

    /** Archmage. */
    private static BufferedImage archmage(int frame) {
        BufferedImage im = img64();
        Graphics2D g = gfx(im);
        // kontos
        g.setColor(new Color(40, 60, 200));
        g.fillPolygon(new Polygon(new int[]{14, 50, 32}, new int[]{62, 62, 14}, 3));
        g.setColor(new Color(70, 100, 255));
        g.fillPolygon(new Polygon(new int[]{20, 44, 32}, new int[]{62, 62, 22}, 3));
        // csillagok a kontoson
        g.setColor(new Color(255, 230, 120));
        g.fillOval(28, 40, 4, 4);
        g.fillOval(36, 50, 3, 3);
        g.fillOval(24, 52, 3, 3);
        // fej
        g.setColor(new Color(240, 205, 170));
        g.fillOval(25, 14, 14, 13);
        // szakall
        g.setColor(Color.WHITE);
        g.fillOval(26, 22, 12, 10);
        // kalap
        g.setColor(new Color(30, 40, 150));
        g.fillPolygon(new Polygon(new int[]{18, 46, 32}, new int[]{18, 18, 0}, 3));
        g.fillRect(16, 16, 32, 4);
        // szemek (izzo)
        g.setColor(frame == 0 ? new Color(90, 240, 255) : Color.WHITE);
        g.fillOval(28, 17, 3, 3);
        g.fillOval(34, 17, 3, 3);
        // varazsbot
        g.setColor(new Color(120, 80, 40));
        g.setStroke(new BasicStroke(3f));
        g.drawLine(52, 62, 52, 20);
        g.setColor(frame == 0 ? new Color(255, 90, 40) : new Color(255, 200, 80));
        g.fillOval(47, 12, 11, 11);
        g.dispose();
        return im;
    }

    private static BufferedImage bolt(Color c, Color core) {
        BufferedImage im = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = gfx(im);
        g.setPaint(new RadialGradientPaint(new Point(8, 8), 8,
                new float[]{0f, 0.45f, 1f},
                new Color[]{core, c, new Color(c.getRed(), c.getGreen(), c.getBlue(), 0)}));
        g.fillOval(0, 0, 16, 16);
        g.dispose();
        return im;
    }

    private static BufferedImage boom(int i, int n) {
        BufferedImage im = img64();
        Graphics2D g = gfx(im);
        float t = (float) i / (n - 1);
        int alpha = (int) (235 * (1 - t * 0.75f));
        Color c1 = new Color(255, 255, 220, alpha);
        Color c2 = new Color(255, 150 - (int) (90 * t), 30, (int) (alpha * 0.8f));
        Color c3 = new Color(255, 60, 20, 0);
        int rad = 10 + (int) (22 * t);
        g.setPaint(new RadialGradientPaint(new Point(32, 32), rad,
                new float[]{0f, 0.55f, 1f}, new Color[]{c1, c2, c3}));
        g.fillOval(32 - rad, 32 - rad, rad * 2, rad * 2);
        Random r = new Random(42 + i);
        g.setColor(new Color(255, 230, 150, alpha));
        for (int k = 0; k < 10; k++) {
            double a = r.nextDouble() * Math.PI * 2;
            int d = rad + r.nextInt(8);
            int x = 32 + (int) (Math.cos(a) * d), y = 32 + (int) (Math.sin(a) * d);
            g.fillOval(x - 2, y - 2, 4, 4);
        }
        g.dispose();
        return im;
    }
}

/* ============================= HANG ============================= */

class Sound {
    Clip cShoot, cEnemyShoot, cBoom, cDie, cWarp, cAppear, cMage, cTeleport, cExtra;
    private boolean ok = true;

    Sound() {
        try {
            cShoot = make(950, 190, 110, 0);
            cEnemyShoot = make(420, 90, 160, 0);
            cBoom = make(300, 40, 260, 2);
            cDie = make(600, 50, 650, 1);
            cWarp = make(200, 1100, 180, 1);
            cAppear = make(330, 660, 240, 0);
            cMage = make(900, 300, 420, 0);
            cTeleport = make(1200, 400, 90, 1);
            cExtra = make(520, 1040, 300, 1);
        } catch (Throwable t) {
            ok = false;
        }
    }

    private Clip make(double f0, double f1, int ms, int wave) throws Exception {
        int sr = 22050;
        int n = sr * ms / 1000;
        byte[] b = new byte[n];
        double ph = 0;
        Random r = new Random(1);
        for (int i = 0; i < n; i++) {
            double t = (double) i / n;
            double f = f0 + (f1 - f0) * t;
            ph += 2 * Math.PI * f / sr;
            double v;
            if (wave == 0) v = Math.signum(Math.sin(ph));
            else if (wave == 1) v = Math.sin(ph);
            else v = r.nextDouble() * 2 - 1;
            double env = Math.min(1, 8.0 * (1 - t)) * Math.min(1, i / 180.0);
            b[i] = (byte) (v * 48 * env);
        }
        AudioFormat fmt = new AudioFormat(22050f, 8, 1, true, false);
        Clip c = AudioSystem.getClip();
        c.open(fmt, b, 0, n);
        return c;
    }

    void play(Clip c) {
        if (!ok || c == null) return;
        try {
            c.stop();
            c.setFramePosition(0);
            c.start();
        } catch (Throwable ignored) {}
    }
}
