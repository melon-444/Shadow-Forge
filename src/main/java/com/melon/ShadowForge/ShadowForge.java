package com.melon.ShadowForge;

import com.melon.foolsEngine.api.input.Action;
import com.melon.foolsEngine.api.input.FoolsEngineKeyCode;
import com.melon.foolsEngine.api.input.InputManager;
import com.melon.foolsEngine.api.rendering.render.RenderFrame;
import com.melon.foolsEngine.api.rendering.render.RenderTarget;
import com.melon.foolsEngine.api.rendering.resource.*;
import com.melon.foolsEngine.api.rendering.shader.ShaderProgram;
import com.melon.foolsEngine.api.windows.Window;
import com.melon.foolsEngine.api.windows.WindowsManager;
import com.melon.foolsEngine.core.FoolsEngine;
import com.melon.foolsEngine.util.*;
import com.melon.foolsEngine.util.imgui.ImGuiContext;
import com.melon.foolsEngine.util.imgui.ImGuiRenderer;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiInputTextFlags;
import imgui.type.ImString;
import imgui.type.ImFloat;
import org.joml.*;
import org.joml.Math;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ShadowForge {
    static FoolsEngine foolsEngine = FoolsEngine.create(1000, 100, 800, 600);

    private static final String DEPTH_MAPS_DIR = "depth_maps";
    private static final float HEIGHT_SCALE = 10.0f;
    private static final float CELL_SIZE = 0.5f;
    private static final int SHADOW_MAP_SIZE = 8192;
    private static final int MAX_SHADOW_LAYERS = 16;
    private static final float SPOT_SHADOW_NEAR = 0.1f;

    private List<Path> depthMapFiles = new ArrayList<>();
    private int currentIndex = -1;

    private float[] currentDepthData;
    private int depthWidth;
    private int depthHeight;

    private Mesh terrainMesh;
    private Material material;
    private Material depthMaterial;
    private LightEnvironment lightEnv;
    private Camera camera;
    private PerspectiveProjection proj;

    private Vector3f cameraPos = new Vector3f();
    private Vector3f cameraTarget = new Vector3f();
    private Vector3f worldUp = new Vector3f(0, 1, 0);

    private float yaw;
    private float pitch;
    private float moveSpeed = 15.0f;
    private float lookSensitivity = 1.0f;

    private RenderScene scene;
    private java.util.Random rng = new java.util.Random();
    private RenderFrame frame;

    private Deque<Light> lightStack = new ArrayDeque<>();

    private RenderTarget shadowArray;
    private boolean shadowsEnabled;
    private TextureManager textureManager;

    private ImGuiContext imGuiContext;
    private ImGuiRenderer imGuiRenderer;

    private int nextLightId = 1;
    private final IdentityHashMap<Light, Integer> lightIds = new IdentityHashMap<>();

    static class LightEdit {
        int id;
        float[] color = new float[3];
        String hex;
        ImString hexBuf = new ImString(16);
        float[] dir = new float[2];
        float[] pos = new float[3];
        float intensity;
        boolean hexActive;
        LightEdit(int id) { this.id = id; }
    }
    private final Map<Integer, LightEdit> edits = new LinkedHashMap<>();
    private int selectedLightId = -1;

    private Vector3f initCameraPos = new Vector3f();
    private Vector3f initCameraTarget = new Vector3f();
    private float initYaw, initPitch;
    private final Vector3f defaultAmbient = new Vector3f(0.15f, 0.15f, 0.15f);

    private int screenshotCounter;
    private String screenshotPrefix = "";
    private static final String SCREENSHOT_DIR = "screenshots";

    private float depthCutoff;

    public static void main(String[] args) {
        new ShadowForge().run();
    }

    public void run() {
        WindowsManager manager = foolsEngine.serviceFactory.getWindowsManager();
        Window win = foolsEngine.mainWindow;
        win.setTitle("ShadowForge  |  P:Dir O:Point I:Spot ,:ShwDir .:ShwSpot U:Undo L:Clear  N:Next B:Prev ESC:Exit");
        win.setSize(1024, 768);

        enumerateDepthMaps();
        if (depthMapFiles.isEmpty()) {
            System.err.println("No PNG files found in " + DEPTH_MAPS_DIR);
            return;
        }

        currentIndex = 0;
        loadDepthMap(depthMapFiles.get(currentIndex));

        proj = new PerspectiveProjection(foolsEngine.FOV, foolsEngine.aspect, foolsEngine.Z_NEAR);
        initCamera(proj);

        ShaderProgram shader = foolsEngine.serviceFactory.getShaderProgram();
        shader.load(
                Path.of("src/main/resources/shader/vsh/main_vsh.glsl"),
                Path.of("src/main/resources/shader/fsh/main_fsh.glsl")
        );
        material = new Material(shader);

        textureManager = foolsEngine.serviceFactory.createTextureManager(
                Math.max(depthWidth, 64), Math.max(depthHeight, 64), 64);
        Texture depthTex = textureManager.upload(depthMapFiles.get(currentIndex));
        material.set("textureSampler", depthTex);

        buildTerrainMesh();

        ShaderProgram dShader = foolsEngine.serviceFactory.getShaderProgram();
        dShader.load(
                Path.of("src/main/resources/shader/vsh/depth_vsh.glsl"),
                Path.of("src/main/resources/shader/fsh/depth_fsh.glsl")
        );
        depthMaterial = new Material(dShader);

        lightEnv = new LightEnvironment();
        lightEnv.setAmbient(0.15f, 0.15f, 0.15f);
        lightEnv.setShadowMapSize(SHADOW_MAP_SIZE);

        shadowArray = foolsEngine.serviceFactory.createRenderTarget(
                SHADOW_MAP_SIZE, SHADOW_MAP_SIZE, RenderTarget.TARGET_DEPTH, MAX_SHADOW_LAYERS);

        addLight(Light.directional(new Vector3f(1.0f, 0.95f, 0.8f), new Vector3f(0.5f, -1f, 0.3f), 0.8f));
        addLight(Light.directional(new Vector3f(0.5f, 0.6f, 1.0f), new Vector3f(-0.5f, -0.3f, -0.5f), 0.4f));

        win.show();
        frame = foolsEngine.frame;
        frame.init();

        saveInitialState();

        imGuiContext = new ImGuiContext();
        imGuiContext.init(win.getID(), "#version 330");
        imGuiRenderer = new ImGuiRenderer(imGuiContext);

        scene = new RenderScene();
        scene.setLighting(lightEnv);
        scene.setCamera(camera);
        scene.setTextureManager(textureManager);

        InputManager input = foolsEngine.serviceFactory.createInputManager(win);
        win.setCursorMode(CursorMode.DISABLED);

        Action moveForward = a(SignalType.BUTTON);
        Action moveBackward = a(SignalType.BUTTON);
        Action moveLeft = a(SignalType.BUTTON);
        Action moveRight = a(SignalType.BUTTON);
        Action moveUp = a(SignalType.BUTTON);
        Action moveDown = a(SignalType.BUTTON);
        Action lookDelta = a(SignalType.AXIS_2DDel);
        Action nextImage = a(SignalType.BUTTON);
        Action prevImage = a(SignalType.BUTTON);
        Action exit = a(SignalType.BUTTON);
        Action switchMouseMode = a(SignalType.BUTTON);
        Action spawnDirLight = a(SignalType.BUTTON);
        Action spawnPointLight = a(SignalType.BUTTON);
        Action spawnSpotLight = a(SignalType.BUTTON);
        Action spawnShadowDirLight = a(SignalType.BUTTON);
        Action spawnShadowSpotLight = a(SignalType.BUTTON);
        Action undoLight = a(SignalType.BUTTON);
        Action clearLights = a(SignalType.BUTTON);
        Action ambientUp = a(SignalType.BUTTON);
        Action ambientDown = a(SignalType.BUTTON);
        Action speedUp = a(SignalType.AXIS_1D);
        Action speedDown = a(SignalType.AXIS_1D);
        Action togglePanel = a(SignalType.BUTTON);
        Action screenshot = a(SignalType.BUTTON);
        Action resetAll = a(SignalType.BUTTON);

        input.bind(input.getKeyboard(), FoolsEngineKeyCode.W, moveForward);
        input.bind(input.getKeyboard(), FoolsEngineKeyCode.S, moveBackward);
        input.bind(input.getKeyboard(), FoolsEngineKeyCode.A, moveLeft);
        input.bind(input.getKeyboard(), FoolsEngineKeyCode.D, moveRight);
        input.bind(input.getKeyboard(), FoolsEngineKeyCode.SPACE, moveUp);
        input.bind(input.getKeyboard(), FoolsEngineKeyCode.LEFT_SHIFT, moveDown);
        input.bind(input.getKeyboard(), FoolsEngineKeyCode.N, nextImage);
        input.bind(input.getKeyboard(), FoolsEngineKeyCode.B, prevImage);
        input.bind(input.getKeyboard(), FoolsEngineKeyCode.ESC, exit);
        input.bind(input.getMouse(), FoolsEngineKeyCode.CURSOR, lookDelta);
        input.bind(input.getMouse(), FoolsEngineKeyCode.MOUSE_RIGHT, switchMouseMode);
        input.bind(input.getKeyboard(), FoolsEngineKeyCode.P, spawnDirLight);
        input.bind(input.getKeyboard(), FoolsEngineKeyCode.O, spawnPointLight);
        input.bind(input.getKeyboard(), FoolsEngineKeyCode.I, spawnSpotLight);
        input.bind(input.getKeyboard(), FoolsEngineKeyCode.COMMA, spawnShadowDirLight);
        input.bind(input.getKeyboard(), FoolsEngineKeyCode.PERIOD, spawnShadowSpotLight);
        input.bind(input.getKeyboard(), FoolsEngineKeyCode.U, undoLight);
        input.bind(input.getKeyboard(), FoolsEngineKeyCode.L, clearLights);
        input.bind(input.getKeyboard(), FoolsEngineKeyCode.J, ambientUp);
        input.bind(input.getKeyboard(), FoolsEngineKeyCode.K, ambientDown);
        input.bind(input.getMouse(), FoolsEngineKeyCode.MOUSE_MIDDLE, speedDown);
        input.bind(input.getMouse(), FoolsEngineKeyCode.MOUSE_MIDDLE, speedUp);
        input.bind(input.getKeyboard(), FoolsEngineKeyCode.M, togglePanel);
        input.bind(input.getKeyboard(), FoolsEngineKeyCode.F12, screenshot);
        input.bind(input.getKeyboard(), FoolsEngineKeyCode.R, resetAll);

        long lastTime = System.nanoTime();
        boolean showPanel = true;

        while (!win.shouldClose()) {
            long currentTime = System.nanoTime();
            float deltaTime = (currentTime - lastTime) / 1e9f;
            lastTime = currentTime;

            input.beginFrame();

            Vector3f forward = new Vector3f(cameraTarget).sub(cameraPos).normalize();
            Vector3f right = new Vector3f(forward).cross(worldUp).normalize();
            Vector3f lookDir = new Vector3f();

            if (input.isActionDown(moveForward)) cameraPos.add(new Vector3f(forward).mul(moveSpeed * deltaTime));
            if (input.isActionDown(moveBackward)) cameraPos.sub(new Vector3f(forward).mul(moveSpeed * deltaTime));
            if (input.isActionDown(moveRight)) cameraPos.add(new Vector3f(right).mul(moveSpeed * deltaTime));
            if (input.isActionDown(moveLeft)) cameraPos.sub(new Vector3f(right).mul(moveSpeed * deltaTime));
            if (input.isActionDown(moveUp)) cameraPos.add(new Vector3f(worldUp).mul(moveSpeed * deltaTime));
            if (input.isActionDown(moveDown)) cameraPos.sub(new Vector3f(worldUp).mul(moveSpeed * deltaTime));

            if (input.isActionPressed(nextImage)) nextDepthMap();
            if (input.isActionPressed(prevImage)) prevDepthMap();
            if (input.isActionPressed(switchMouseMode)) toggleCursorMode(win);

            if (input.getActionAxis1D(speedUp) > 0.0) { moveSpeed = Math.min(moveSpeed * 1.5f, 500f); printSpeed(); }
            if (input.getActionAxis1D(speedDown) < 0.0) { moveSpeed = Math.max(moveSpeed / 1.5f, 0.1f); printSpeed(); }
            if (input.isActionPressed(togglePanel)) { showPanel = !showPanel; }
            if (input.isActionPressed(screenshot)) { saveScreenshot(); }
            if (input.isActionPressed(resetAll)) { resetAllState(); }

            updateCameraLook(input, lookDelta, win, lookDir);

            if (input.isActionPressed(spawnDirLight))
                addLight(Light.directional(randColor(), new Vector3f(lookDir)));
            if (input.isActionPressed(spawnPointLight))
                addLight(Light.point(randColor(), new Vector3f(cameraPos), 3.0f));
            if (input.isActionPressed(spawnSpotLight))
                addLight(Light.spot(randColor(), new Vector3f(lookDir), new Vector3f(cameraPos), 10f, 10f, 2.0f));
            if (input.isActionPressed(spawnShadowDirLight)) {
                enableShadowsOnce();
                Light base = Light.directional(randColor(), lookDir, 1.0f);
                addLight(lightEnv.enableDirLightShadow(base, camera));
            }
            if (input.isActionPressed(spawnShadowSpotLight)) {
                enableShadowsOnce();
                Light base = Light.spot(randColor(), lookDir, new Vector3f(cameraPos), 10f, 10f, 2.0f);
                addLight(lightEnv.enableSpotLightShadow(base, SPOT_SHADOW_NEAR));
            }
            if (input.isActionPressed(undoLight)) undoLastLight();
            if (input.isActionPressed(clearLights)) clearAllLights();

            if (input.isActionPressed(ambientUp)) { lightEnv.getAmbient().mul(1.1f); printAmbient(); }
            if (input.isActionPressed(ambientDown)) { lightEnv.getAmbient().mul(0.9f); printAmbient(); }

            if (input.isActionDown(exit)) break;

            cameraTarget = new Vector3f(cameraPos).add(lookDir);
            camera.view.identity().lookAt(cameraPos, cameraTarget, worldUp);

            scene.clear();
            scene.setTextureManager(textureManager);
            scene.setLighting(lightEnv);
            scene.setCamera(camera);
            scene.setBackGroundColor(0.1f, 0.05f, 0.05f, 1.0f);
            scene.submit(new RenderCommand(terrainMesh, material, new Matrix4f()));

            frame.render(scene);

            if (showPanel) {
                imGuiRenderer.beginFrame();
                renderLightPanel();
                imGuiRenderer.endFrame();
            }

            input.endFrame();
            win.update();
        }

        shader.destroy();
        dShader.destroy();
        if (shadowsEnabled) lightEnv.destroy();
        imGuiContext.destroy();
        manager.destroyWindow(win, true);
    }

    private void renderLightPanel() {
        ImGui.setNextWindowSize(370, 580, ImGuiCond.Once);
        ImGui.setNextWindowPos(10, 10, ImGuiCond.Once);
        ImGui.begin("ShadowForge");

        List<Light> lights = new ArrayList<>(lightEnv.getLights());
        syncEditState(lights);

        if (ImGui.button("Screenshot")) { saveScreenshot(); }
        ImGui.sameLine();
        if (ImGui.button("Reset All")) { resetAllState(); }
        ImGui.sameLine();
        if (ImGui.button("Next")) { nextDepthMap(); }
        ImGui.sameLine();
        if (ImGui.button("Prev")) { prevDepthMap(); }

        ImString ssPrefix = new ImString(screenshotPrefix, 32);
        if (ImGui.inputText("SS Prefix", ssPrefix)) { screenshotPrefix = ssPrefix.get().trim(); }

        float[] cutoffBuf = new float[]{depthCutoff};
        ImFloat cutoffIn = new ImFloat(depthCutoff);
        if (ImGui.inputFloat("Cutoff", cutoffIn)) {
            depthCutoff = Math.max(0f, Math.min(1f, cutoffIn.get()));
            buildTerrainMesh();
        }
        if (ImGui.sliderFloat("Cutoff##sl", cutoffBuf, 0f, 1f)) {
            depthCutoff = cutoffBuf[0];
            buildTerrainMesh();
        }

        ImGui.text(String.format("Ambient: (%.3f, %.3f, %.3f)",
                lightEnv.getAmbient().x, lightEnv.getAmbient().y, lightEnv.getAmbient().z));
        ImGui.sameLine();
        if (ImGui.button("+")) { lightEnv.getAmbient().mul(1.1f); }
        ImGui.sameLine();
        if (ImGui.button("-")) { lightEnv.getAmbient().mul(0.9f); }

        ImGui.separator();
        ImGui.text(String.format("Light Count: %d", lights.size()));

        if (!lights.isEmpty()) {
            for (Light l : lights) {
                int id = lightIds.getOrDefault(l, 0);
                LightEdit e = edits.get(id);
                if (e == null) continue;
                String label = String.format("#%d %s%s",
                        id, typeLabel(l.type), l.castsShadow() ? " (Shadow)" : "");
                if (ImGui.selectable(label, selectedLightId == id)) {
                    selectedLightId = (selectedLightId == id) ? -1 : id;
                }
            }
            ImGui.separator();
            if (selectedLightId > 0) {
                LightEdit e = edits.get(selectedLightId);
                Light selected = null;
                for (Light l : lights) {
                    if (lightIds.getOrDefault(l, 0) == selectedLightId) { selected = l; break; }
                }
                if (e != null && selected != null) {
                    renderLightEditor(selected, e);
                }
            }
        } else {
            ImGui.text("No lights. Press P/O/I/,/./ to add.");
        }

        ImGui.separator();
        if (ImGui.collapsingHeader("Controls / Keybinds")) {
            ImGui.text("N/B    Next/Prev Image");
            ImGui.text("P      Add Directional Light");
            ImGui.text("O      Add Point Light");
            ImGui.text("I      Add Spot Light");
            ImGui.text(",      Add Shadow Dir Light");
            ImGui.text(".      Add Shadow Spot Light");
            ImGui.text("U      Undo Last Light");
            ImGui.text("L      Clear All Lights");
            ImGui.text("J/K    Ambient Up/Down");
            ImGui.text("F12    Screenshot");
            ImGui.text("R      Reset All State");
            ImGui.text("M      Toggle Panel");
            ImGui.text("=/MW   Speed Up/Down");
            ImGui.text("WASD   Move Camera");
            ImGui.text("RMB    Toggle Cursor Capture");
        }

        ImGui.end();
    }

    private void renderLightEditor(Light l, LightEdit e) {
        String sid = "##l" + e.id;

        ImGui.text(String.format("Editing Light #%d (%s)", e.id, typeLabel(l.type)));
        ImGui.separator();

        String storedHex = e.hex;
        if (!storedHex.equals(e.hexBuf.get()) && !e.hexActive) {
            e.hexBuf = new ImString(storedHex, 16);
        }
        if (ImGui.inputText("Hex Color" + sid, e.hexBuf, ImGuiInputTextFlags.CharsHexadecimal | ImGuiInputTextFlags.EnterReturnsTrue)) {
            e.hex = e.hexBuf.get();
            float[] parsed = parseHex(e.hex);
            if (parsed != null) {
                e.color[0] = parsed[0]; e.color[1] = parsed[1]; e.color[2] = parsed[2];
                replaceLight(l);
            }
        }
        e.hexActive = ImGui.isItemActive();
        if (ImGui.isItemDeactivatedAfterEdit()) {
            e.hex = e.hexBuf.get();
            float[] parsed = parseHex(e.hex);
            if (parsed != null) {
                e.color[0] = parsed[0]; e.color[1] = parsed[1]; e.color[2] = parsed[2];
                e.hex = rgbToHex(e.color[0], e.color[1], e.color[2]);
                replaceLight(l);
            }
        }
        ImGui.sameLine();
        ImGui.colorButton("preview" + sid, new float[]{e.color[0], e.color[1], e.color[2], 1f});

        boolean changed = false;

        if (l.type != LightType.POINT) {
            ImFloat yawV = new ImFloat(e.dir[0]);
            ImFloat pitchV = new ImFloat(e.dir[1]);
            if (ImGui.inputFloat("Yaw" + sid, yawV)) { e.dir[0] = yawV.get(); changed = true; }
            float[] yawSl = new float[]{e.dir[0]};
            if (ImGui.sliderFloat("Yaw##sl" + sid, yawSl, -180f, 180f)) { e.dir[0] = yawSl[0]; changed = true; }
            if (ImGui.inputFloat("Pitch" + sid, pitchV)) { e.dir[1] = pitchV.get(); changed = true; }
            float[] piSl = new float[]{e.dir[1]};
            if (ImGui.sliderFloat("Pitch##sl" + sid, piSl, -89f, 89f)) { e.dir[1] = piSl[0]; changed = true; }
        }

        if (l.type == LightType.POINT || l.type == LightType.SPOT) {
            ImFloat xV = new ImFloat(e.pos[0]);
            ImFloat yV = new ImFloat(e.pos[1]);
            ImFloat zV = new ImFloat(e.pos[2]);
            if (ImGui.inputFloat("Pos X" + sid, xV)) { e.pos[0] = xV.get(); changed = true; }
            float[] xSl = new float[]{e.pos[0]};
            if (ImGui.sliderFloat("Pos X##sl" + sid, xSl, -200f, 200f)) { e.pos[0] = xSl[0]; changed = true; }
            if (ImGui.inputFloat("Pos Y" + sid, yV)) { e.pos[1] = yV.get(); changed = true; }
            float[] ySl = new float[]{e.pos[1]};
            if (ImGui.sliderFloat("Pos Y##sl" + sid, ySl, -200f, 200f)) { e.pos[1] = ySl[0]; changed = true; }
            if (ImGui.inputFloat("Pos Z" + sid, zV)) { e.pos[2] = zV.get(); changed = true; }
            float[] zSl = new float[]{e.pos[2]};
            if (ImGui.sliderFloat("Pos Z##sl" + sid, zSl, -200f, 200f)) { e.pos[2] = zSl[0]; changed = true; }
        }

        ImFloat intensV = new ImFloat(e.intensity);
        if (ImGui.inputFloat("Intensity" + sid, intensV)) {
            e.intensity = Math.max(0.01f, intensV.get());
            replaceLight(l);
        }
        float[] iSl = new float[]{e.intensity};
        if (ImGui.sliderFloat("Intensity##sl" + sid, iSl, 0.01f, 20f)) {
            e.intensity = Math.max(0.01f, iSl[0]);
            replaceLight(l);
        }

        if (changed) {
            replaceLight(l);
        }
    }

    private void replaceLight(Light old) {
        Integer id = lightIds.get(old);
        if (id == null) return;
        LightEdit e = edits.get(id);
        if (e == null) return;

        Vector3f color = new Vector3f(e.color[0], e.color[1], e.color[2]);
        float yawR = Math.toRadians(e.dir[0]);
        float pitchR = Math.toRadians(e.dir[1]);
        Vector3f direction = new Vector3f(
                Math.sin(yawR) * Math.cos(pitchR),
                Math.sin(pitchR),
                Math.cos(yawR) * Math.cos(pitchR)
        ).normalize();
        Vector3f position = new Vector3f(e.pos[0], e.pos[1], e.pos[2]);

        Light newLight;
        switch (old.type) {
            case PARALLEL:
                newLight = Light.directional(color, direction, e.intensity);
                break;
            case POINT:
                newLight = Light.point(color, position, e.intensity);
                break;
            case SPOT:
                newLight = Light.spot(color, direction, position, old.innerTheta, old.outerTheta, e.intensity);
                break;
            default:
                return;
        }

        if (old.castsShadow()) {
            if (old.type == LightType.PARALLEL)
                newLight = lightEnv.enableDirLightShadow(newLight, camera);
            else if (old.type == LightType.SPOT)
                newLight = lightEnv.enableSpotLightShadow(newLight, SPOT_SHADOW_NEAR);
        }

        lightEnv.remove(old);
        lightEnv.add(newLight);
        lightIds.remove(old);
        lightIds.put(newLight, id);
        e.hex = rgbToHex(e.color[0], e.color[1], e.color[2]);
        if (!e.hexActive) e.hexBuf = new ImString(e.hex, 16);

        replaceInStack(old, newLight);
    }

    private void replaceInStack(Light old, Light newLight) {
        Deque<Light> newStack = new ArrayDeque<>();
        while (!lightStack.isEmpty()) {
            Light l = lightStack.pollLast();
            newStack.push(l == old ? newLight : l);
        }
        while (!newStack.isEmpty()) {
            lightStack.push(newStack.pollFirst());
        }
    }

    private void syncEditState(List<Light> lights) {
        Set<Integer> activeIds = new HashSet<>();
        for (Light l : lights) {
            Integer id = lightIds.get(l);
            if (id == null) {
                id = nextLightId++;
                lightIds.put(l, id);
            }
            activeIds.add(id);
            edits.computeIfAbsent(id, LightEdit::new);
            syncEditFromLight(edits.get(id), l);
        }
        edits.keySet().removeIf(k -> !activeIds.contains(k));
        if (selectedLightId >= 0 && !activeIds.contains(selectedLightId)) {
            selectedLightId = lights.isEmpty() ? -1 : lightIds.getOrDefault(lights.get(0), -1);
        }
    }

    private static float[] parseHex(String hex) {
        String h = hex.replace("#", "").trim();
        if (h.length() < 6) return null;
        try {
            int rgb = Integer.parseInt(h.substring(0, 6), 16);
            return new float[]{
                    ((rgb >> 16) & 0xFF) / 255.0f,
                    ((rgb >> 8) & 0xFF) / 255.0f,
                    (rgb & 0xFF) / 255.0f
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String rgbToHex(float r, float g, float b) {
        int ir = Math.max(0, Math.min(255, (int) (r * 255)));
        int ig = Math.max(0, Math.min(255, (int) (g * 255)));
        int ib = Math.max(0, Math.min(255, (int) (b * 255)));
        return String.format("#%02X%02X%02X", ir, ig, ib);
    }

    private static String typeLabel(LightType t) {
        switch (t) {
            case PARALLEL: return "Dir";
            case POINT: return "Point";
            case SPOT: return "Spot";
            default: return "?";
        }
    }

    private void updateCameraLook(InputManager input, Action lookDelta, Window win, Vector3f lookDirOut) {
        Vector2f mouseDelta = win.getCursorMode() == CursorMode.DISABLED
                ? input.getActionAxis2DDelta(lookDelta)
                : new Vector2f(0.0f);
        yaw -= mouseDelta.x * lookSensitivity;
        pitch -= mouseDelta.y * lookSensitivity;
        pitch = Math.min(89.0f, Math.max(-89.0f, pitch));
        lookDirOut.set(
                Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)),
                Math.sin(Math.toRadians(pitch)),
                Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch))
        ).normalize();
    }

    private Vector3f randColor() {
        return new Vector3f(rng.nextFloat(), rng.nextFloat(), rng.nextFloat());
    }

    private void addLight(Light light) {
        lightEnv.add(light);
        lightStack.push(light);
        int id = nextLightId++;
        lightIds.put(light, id);
        edits.put(id, new LightEdit(id));
        syncEditFromLight(edits.get(id), light);
        System.out.printf("+Light #%d (%d total) type=%s%n", id, lightEnv.size(), light.type);
    }

    private void syncEditFromLight(LightEdit e, Light l) {
        e.color[0] = l.color.x; e.color[1] = l.color.y; e.color[2] = l.color.z;
        e.hex = rgbToHex(l.color.x, l.color.y, l.color.z);
        if (!e.hexActive) e.hexBuf = new ImString(e.hex, 16);
        e.intensity = l.intensity;
        if (l.type == LightType.POINT) {
            e.dir[0] = 0; e.dir[1] = 0;
        } else {
            Vector3f d = new Vector3f(l.direction).normalize();
            e.dir[0] = (float) Math.toDegrees(Math.atan2(d.x, d.z));
            e.dir[1] = (float) Math.toDegrees(Math.asin(d.y));
        }
        e.pos[0] = l.position.x; e.pos[1] = l.position.y; e.pos[2] = l.position.z;
    }

    private void undoLastLight() {
        if (lightStack.isEmpty()) { System.out.println("Undo: nothing to undo"); return; }
        Light removed = lightStack.pop();
        Integer id = lightIds.remove(removed);
        if (id != null) {
            edits.remove(id);
            if (selectedLightId == id) selectedLightId = -1;
        }
        lightEnv.remove(removed);
        System.out.printf("-Light (undo) => %d remaining%n", lightEnv.size());
    }

    private void clearAllLights() {
        int count = lightEnv.size();
        lightStack.clear();
        lightEnv.clear();
        lightIds.clear();
        edits.clear();
        selectedLightId = -1;
        nextLightId = 1;
        System.out.printf("Cleared %d lights%n", count);
    }

    private void saveInitialState() {
        initCameraPos.set(cameraPos);
        initCameraTarget.set(cameraTarget);
        initYaw = yaw;
        initPitch = pitch;
    }

    private void resetAllState() {
        clearAllLights();
        nextLightId = 1;
        lightEnv.setAmbient(defaultAmbient.x, defaultAmbient.y, defaultAmbient.z);
        cameraPos.set(initCameraPos);
        cameraTarget.set(initCameraTarget);
        yaw = initYaw;
        pitch = initPitch;
        camera.view.identity().lookAt(cameraPos, cameraTarget, worldUp);
        addLight(Light.directional(new Vector3f(1.0f, 0.95f, 0.8f), new Vector3f(0.5f, -1f, 0.3f), 0.8f));
        addLight(Light.directional(new Vector3f(0.5f, 0.6f, 1.0f), new Vector3f(-0.5f, -0.3f, -0.5f), 0.4f));
        System.out.println("Reset: camera, ambient restored; initial lights re-added.");
    }

    private void saveScreenshot() {
        try {
            Path dir = Path.of(SCREENSHOT_DIR);
            if (!Files.isDirectory(dir)) Files.createDirectories(dir);
            String prefix = screenshotPrefix.isEmpty() ? "screenshot" : screenshotPrefix;
            String name = String.format("%s_%04d.png", prefix, ++screenshotCounter);
            Path file = dir.resolve(name);
            frame.screenShot(file);
            System.out.println("Screenshot saved: " + file);
        } catch (Exception e) {
            System.err.println("Screenshot failed: " + e.getMessage());
        }
    }

    private void enableShadowsOnce() {
        if (!shadowsEnabled) {
            lightEnv.enableShadows(shadowArray, depthMaterial, MAX_SHADOW_LAYERS);
            shadowsEnabled = true;
            System.out.println("Shadow mapping enabled");
        }
    }

    private void toggleCursorMode(Window win) {
        win.setCursorMode(win.getCursorMode() == CursorMode.DISABLED ? CursorMode.NORMAL : CursorMode.DISABLED);
    }

    private void printSpeed() { System.out.printf("Speed: %.1f%n", moveSpeed); }
    private void printAmbient() {
        System.out.printf("Ambient: %.3f %.3f %.3f%n",
                lightEnv.getAmbient().x, lightEnv.getAmbient().y, lightEnv.getAmbient().z);
    }

    private static Action a(SignalType type) {
        return () -> type;
    }

    private void enumerateDepthMaps() {
        Path dir = Path.of(DEPTH_MAPS_DIR);
        if (!Files.isDirectory(dir)) return;
        try (var files = Files.list(dir)) {
            depthMapFiles = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png")||p.getFileName().toString().toLowerCase().endsWith(".jpg"))
                    .sorted(Comparator.comparing(Path::getFileName, Comparator.naturalOrder()))
                    .toList();
        } catch (Exception e) {
            System.err.println("Failed to enumerate depth maps: " + e.getMessage());
        }
        System.out.println("Found " + depthMapFiles.size() + " PNG files in " + DEPTH_MAPS_DIR);
    }

    private void loadDepthMap(Path path) {
        System.out.println("Loading: " + path.getFileName());

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer c = stack.mallocInt(1);

            ByteBuffer image = STBImage.stbi_load(path.toString(), w, h, c, 0);
            if (image == null)
                throw new RuntimeException("Failed to load image: " + STBImage.stbi_failure_reason());

            depthWidth = w.get(0);
            depthHeight = h.get(0);
            int channels = c.get(0);

            currentDepthData = new float[depthWidth * depthHeight];

            float minD = Float.MAX_VALUE, maxD = Float.MIN_VALUE;

            for (int y = 0; y < depthHeight; y++) {
                for (int x = 0; x < depthWidth; x++) {
                    int idx = (y * depthWidth + x) * channels;
                    float depth;

                    if (channels == 1) {
                        depth = (image.get(idx) & 0xFF) / 255.0f;
                    } else {
                        float r = (image.get(idx) & 0xFF) / 255.0f;
                        float g = (image.get(idx + 1) & 0xFF) / 255.0f;
                        float b = (image.get(idx + 2) & 0xFF) / 255.0f;
                        depth = 0.299f * r + 0.587f * g + 0.114f * b;
                    }

                    currentDepthData[y * depthWidth + x] = depth;
                    if (depth < minD) minD = depth;
                    if (depth > maxD) maxD = depth;
                }
            }

            STBImage.stbi_image_free(image);

            float range = maxD - minD;
            if (range > 0.0001f) {
                for (int i = 0; i < currentDepthData.length; i++)
                    currentDepthData[i] = (currentDepthData[i] - minD) / range;
            }

            System.out.printf("  %dx%d ch=%d depth=[%.3f, %.3f]%n",
                    depthWidth, depthHeight, channels, minD, maxD);
        }
    }

    private void initCamera(PerspectiveProjection proj) {
        float hx = depthWidth * CELL_SIZE * 0.5f;
        float hz = depthHeight * CELL_SIZE * 0.5f;
        float cy = HEIGHT_SCALE * 0.5f;
        float dist = Math.max(hx, hz) * 3.0f;

        cameraPos.set(0, cy + dist * 0.3f, -dist);
        cameraTarget.set(0, cy * 0.5f, 0);

        Vector3f d = new Vector3f(cameraTarget).sub(cameraPos).normalize();
        yaw = (float) Math.toDegrees(Math.atan2(d.x, d.z));
        pitch = (float) Math.toDegrees(Math.asin(d.y));

        camera = new Camera(
                new Matrix4f().lookAt(cameraPos, cameraTarget, worldUp),
                proj.get(new Matrix4f())
        );
    }

    private void buildTerrainMesh() {
        int w = depthWidth;
        int h = depthHeight;
        int vc = w * h;

        boolean[] keep = new boolean[vc];
        for (int i = 0; i < vc; i++) {
            keep[i] = currentDepthData[i] > depthCutoff;
        }

        List<Integer> idxList = new ArrayList<>();
        for (int z = 0; z < h - 1; z++) {
            for (int x = 0; x < w - 1; x++) {
                int tl = z * w + x;
                int tr = z * w + (x + 1);
                int bl = (z + 1) * w + x;
                int br = (z + 1) * w + (x + 1);
                if (keep[tl] && keep[tr] && keep[bl] && keep[br]) {
                    idxList.add(tl); idxList.add(bl); idxList.add(tr);
                    idxList.add(tr); idxList.add(bl); idxList.add(br);
                }
            }
        }

        float[] vertices = new float[vc * 8];
        int[] indices = new int[idxList.size()];
        for (int i = 0; i < idxList.size(); i++) indices[i] = idxList.get(i);

        float ox = -(w - 1) * CELL_SIZE * 0.5f;
        float oz = -(h - 1) * CELL_SIZE * 0.5f;

        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int vi = (z * w + x);
                int vi8 = vi * 8;
                float depth = currentDepthData[z * w + x];

                vertices[vi8] = ox + x * CELL_SIZE;
                float d = depth > depthCutoff ? depth : 0f;
                vertices[vi8 + 1] = d * HEIGHT_SCALE;
                vertices[vi8 + 2] = oz + z * CELL_SIZE;
                vertices[vi8 + 3] = (float) x / (w - 1);
                vertices[vi8 + 4] = (float) z / (h - 1);

                float nx = 0, ny = 1, nz = 0;
                if (x > 0 && x < w - 1 && z > 0 && z < h - 1) {
                    float hL = currentDepthData[z * w + (x - 1)] * HEIGHT_SCALE;
                    float hR = currentDepthData[z * w + (x + 1)] * HEIGHT_SCALE;
                    float hD = currentDepthData[(z - 1) * w + x] * HEIGHT_SCALE;
                    float hU = currentDepthData[(z + 1) * w + x] * HEIGHT_SCALE;
                    nx = (hL - hR) / (2 * CELL_SIZE);
                    nz = (hD - hU) / (2 * CELL_SIZE);
                }
                Vector3f n = new Vector3f(nx, ny, nz).normalize();
                vertices[vi8 + 5] = n.x;
                vertices[vi8 + 6] = n.y;
                vertices[vi8 + 7] = n.z;
            }
        }

        VertexLayout layout = new VertexLayout().add(0, 3).add(1, 2).add(2, 3);
        MeshData data = new MeshData(vertices, indices, layout);

        terrainMesh = foolsEngine.serviceFactory.getMesh();
        terrainMesh.upload(data);
        System.out.printf("Terrain: %d verts, %d tris (cutoff=%.2f)%n", vc, indices.length / 3, depthCutoff);
    }

    private void nextDepthMap() {
        if (depthMapFiles.isEmpty()) return;
        currentIndex = (currentIndex + 1) % depthMapFiles.size();
        loadDepthMap(depthMapFiles.get(currentIndex));
        buildTerrainMesh();
        updateTerrainTexture();
    }

    private void prevDepthMap() {
        if (depthMapFiles.isEmpty()) return;
        currentIndex = (currentIndex - 1 + depthMapFiles.size()) % depthMapFiles.size();
        loadDepthMap(depthMapFiles.get(currentIndex));
        buildTerrainMesh();
        updateTerrainTexture();
    }

    private void updateTerrainTexture() {
        textureManager = foolsEngine.serviceFactory.createTextureManager(
                Math.max(depthWidth, 64), Math.max(depthHeight, 64), 64);
        Texture tex = textureManager.upload(depthMapFiles.get(currentIndex));
        material.set("textureSampler", tex);
        scene.setTextureManager(textureManager);
        System.out.println("Terrain texture updated: " + depthMapFiles.get(currentIndex).getFileName());
    }
}
