package com.melon.ShadowForge;

import com.melon.foolsEngine.api.input.Action;
import com.melon.foolsEngine.api.input.FoolsEngineKeyCode;
import com.melon.foolsEngine.api.input.InputManager;
import com.melon.foolsEngine.api.rendering.render.RenderFrame;
import com.melon.foolsEngine.api.rendering.resource.*;
import com.melon.foolsEngine.api.rendering.shader.ShaderProgram;
import com.melon.foolsEngine.api.windows.Window;
import com.melon.foolsEngine.api.windows.WindowsManager;
import com.melon.foolsEngine.core.FoolsEngine;
import com.melon.foolsEngine.util.CursorMode;
import com.melon.foolsEngine.util.PerspectiveProjection;
import com.melon.foolsEngine.util.SignalType;
import com.melon.foolsEngine.util.VertexLayout;
import org.joml.*;
import org.joml.Math;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public class ShadowForge {
    static FoolsEngine foolsEngine = FoolsEngine.create(1000, 100, 800, 600);

    private static final String DEPTH_MAPS_DIR = "src/test/resources/depth_maps";
    private static final float BASE_THICKNESS = 15.0f;
    private static final float HEIGHT_SCALE = 10.0f;

    private List<Path> depthMapFiles = new ArrayList<>();
    private int currentIndex = -1;

    private float[] currentDepthData;
    private int depthWidth;
    private int depthHeight;

    private Mesh terrainMesh;
    private Material material;
    private LightEnvironment lightEnv;
    private Camera camera;

    private Vector3f cameraPos = new Vector3f();
    private Vector3f cameraTarget = new Vector3f();
    private Vector3f worldUp = new Vector3f(0, 1, 0);

    private float yaw = 0;
    private float pitch = 0;
    private float moveSpeed = 5.0f;
    private float lookSensitivity = 1.0f;

    private RenderScene scene;

    public static void main(String[] args) {
        new ShadowForge().run();
    }

    public void run() {
        WindowsManager manager = foolsEngine.serviceFactory.getWindowsManager();
        Window win = foolsEngine.mainWindow;
        win.setTitle("ShadowForge - Phase 1: Terrain Depth Mesh  |  N:Next  B:Prev  ESC:Exit  C:Cursor");
        win.setSize(1024, 768);

        enumerateDepthMaps();
        if (depthMapFiles.isEmpty()) {
            System.err.println("No PNG files found in " + DEPTH_MAPS_DIR);
            System.err.println("Please place depth map PNG images in that directory.");
            return;
        }

        currentIndex = 0;
        loadDepthMap(depthMapFiles.get(currentIndex));

        PerspectiveProjection proj = new PerspectiveProjection(foolsEngine.FOV, foolsEngine.aspect, foolsEngine.Z_NEAR);
        initCamera(proj);

        ShaderProgram shader = foolsEngine.serviceFactory.getShaderProgram();
        shader.load(
                Path.of("src/main/resources/shader/vsh/main_vsh.glsl"),
                Path.of("src/main/resources/shader/fsh/main_fsh.glsl")
        );
        material = new Material(shader);

        buildTerrainMesh();

        lightEnv = new LightEnvironment();
        lightEnv.setAmbient(0.15f, 0.15f, 0.15f);
        lightEnv.setShadowMapSize(4096);
        lightEnv.add(Light.directional(new Vector3f(1.0f, 0.95f, 0.8f), new Vector3f(0.5f, -1f, 0.3f), 0.8f));
        lightEnv.add(Light.directional(new Vector3f(0.5f, 0.6f, 1.0f), new Vector3f(-0.5f, -0.3f, -0.5f), 0.4f));

        win.show();
        RenderFrame frame = foolsEngine.frame;
        frame.init();

        scene = new RenderScene();
        scene.setLighting(lightEnv);
        scene.setCamera(camera);

        InputManager input = foolsEngine.serviceFactory.createInputManager(win);
        win.setCursorMode(CursorMode.DISABLED);

        Action moveForward = () -> SignalType.BUTTON;
        Action moveBackward = () -> SignalType.BUTTON;
        Action moveLeft = () -> SignalType.BUTTON;
        Action moveRight = () -> SignalType.BUTTON;
        Action moveUp = () -> SignalType.BUTTON;
        Action moveDown = () -> SignalType.BUTTON;
        Action lookDelta = () -> SignalType.AXIS_2DDel;
        Action nextImage = () -> SignalType.BUTTON;
        Action prevImage = () -> SignalType.BUTTON;
        Action exit = () -> SignalType.BUTTON;
        Action switchMouseMode = () -> SignalType.BUTTON;
        Action randomColorParallelLight = () -> SignalType.BUTTON;
        Action clearLights = () -> SignalType.BUTTON;

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
        input.bind(input.getKeyboard(), FoolsEngineKeyCode.P, randomColorParallelLight);
        input.bind(input.getKeyboard(), FoolsEngineKeyCode.L, clearLights);

        long lastTime = System.nanoTime();

        Random random = new Random();

        while (!win.shouldClose()) {
            long currentTime = System.nanoTime();
            float deltaTime = (currentTime - lastTime) / 1e9f;
            lastTime = currentTime;

            input.beginFrame();

            Vector3f forward = new Vector3f(cameraTarget).sub(cameraPos).normalize();
            Vector3f right = new Vector3f(forward).cross(worldUp).normalize();

            if (input.isActionDown(moveForward)) {
                cameraPos.add(new Vector3f(forward).mul(moveSpeed * deltaTime));
            }
            if (input.isActionDown(moveBackward)) {
                cameraPos.sub(new Vector3f(forward).mul(moveSpeed * deltaTime));
            }
            if (input.isActionDown(moveRight)) {
                cameraPos.add(new Vector3f(right).mul(moveSpeed * deltaTime));
            }
            if (input.isActionDown(moveLeft)) {
                cameraPos.sub(new Vector3f(right).mul(moveSpeed * deltaTime));
            }
            if (input.isActionDown(moveUp)) {
                cameraPos.add(new Vector3f(worldUp).mul(moveSpeed * deltaTime));
            }
            if (input.isActionDown(moveDown)) {
                cameraPos.sub(new Vector3f(worldUp).mul(moveSpeed * deltaTime));
            }
            if(input.isActionDown(randomColorParallelLight)) {
                lightEnv.add(Light.directional(new Vector3f(random.nextFloat(),random.nextFloat(),random.nextFloat()), new Vector3f(forward)));
            }
            if(input.isActionDown(clearLights)) {
                lightEnv.clear();
            }

            if (input.isActionDown(exit)) {
                break;
            }

            if (input.isActionPressed(nextImage)) {
                nextDepthMap();
            }
            if (input.isActionPressed(prevImage)) {
                prevDepthMap();
            }

            Vector2f mouseDelta = win.getCursorMode() == CursorMode.DISABLED
                    ? input.getActionAxis2DDelta(lookDelta)
                    : new Vector2f(0.0f);
            yaw -= mouseDelta.x * lookSensitivity;
            pitch -= mouseDelta.y * lookSensitivity;
            pitch = Math.min(89.0f, Math.max(-89.0f, pitch));

            Vector3f lookDir = new Vector3f(
                    Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)),
                    Math.sin(Math.toRadians(pitch)),
                    Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch))
            ).normalize();

            cameraTarget = new Vector3f(cameraPos).add(lookDir);
            camera.view.identity().lookAt(cameraPos, cameraTarget, worldUp);

            if (input.isActionPressed(switchMouseMode)) {
                if (win.getCursorMode() == CursorMode.DISABLED)
                    win.setCursorMode(CursorMode.NORMAL);
                else
                    win.setCursorMode(CursorMode.DISABLED);
            }

            scene.clear();
            scene.setCamera(camera);
            scene.setBackGroundColor(0.05f, 0.05f, 0.1f, 1.0f);
            scene.submit(new RenderCommand(terrainMesh, material, new Matrix4f()));

            frame.render(scene);

            input.endFrame();
            win.update();

            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        shader.destroy();
        manager.destroyWindow(win, true);
    }

    private void enumerateDepthMaps() {
        Path dir = Path.of(DEPTH_MAPS_DIR);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            depthMapFiles = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png"))
                    .sorted(Comparator.comparing(Path::getFileName, Comparator.naturalOrder()))
                    .toList();
        } catch (Exception e) {
            System.err.println("Failed to enumerate depth maps: " + e.getMessage());
        }
        System.out.println("Found " + depthMapFiles.size() + " PNG files in " + DEPTH_MAPS_DIR);
    }

    private void loadDepthMap(Path path) {
        System.out.println("Loading depth map: " + path.getFileName());

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer c = stack.mallocInt(1);

            ByteBuffer image = STBImage.stbi_load(path.toString(), w, h, c, 0);
            if (image == null) {
                throw new RuntimeException("Failed to load image: " + STBImage.stbi_failure_reason());
            }

            depthWidth = w.get(0);
            depthHeight = h.get(0);
            int channels = c.get(0);

            currentDepthData = new float[depthWidth * depthHeight];

            float minDepth = Float.MAX_VALUE;
            float maxDepth = Float.MIN_VALUE;

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
                    if (depth < minDepth) minDepth = depth;
                    if (depth > maxDepth) maxDepth = depth;
                }
            }

            STBImage.stbi_image_free(image);

            float range = maxDepth - minDepth;
            if (range > 0.0001f) {
                for (int i = 0; i < currentDepthData.length; i++) {
                    currentDepthData[i] = (currentDepthData[i] - minDepth) / range;
                }
            }

            System.out.printf("  Size: %dx%d, channels: %d, depth range: %.3f - %.3f%n",
                    depthWidth, depthHeight, channels, minDepth, maxDepth);
        }
    }

    private void initCamera(PerspectiveProjection proj) {
        float terrainHalfX = depthWidth * 0.5f;
        float terrainHalfZ = depthHeight * 0.5f;
        float terrainCenterY = HEIGHT_SCALE * 0.5f;

        float dist = Math.max(terrainHalfX, terrainHalfZ) * 2.5f;
        cameraPos.set(0, terrainCenterY + dist * 0.4f, -dist);
        cameraTarget.set(0, terrainCenterY * 0.5f, 0);

        Vector3f lookDir = new Vector3f(cameraTarget).sub(cameraPos).normalize();
        yaw = (float) Math.toDegrees(Math.atan2(lookDir.x, lookDir.z));
        pitch = (float) Math.toDegrees(Math.asin(lookDir.y));

        camera = new Camera(
                new Matrix4f().lookAt(cameraPos, cameraTarget, worldUp),
                proj.get(new Matrix4f())
        );
    }

    private void buildTerrainMesh() {
        int w = depthWidth;
        int h = depthHeight;
        int vertexCount = w * h;
        int quadCount = (w - 1) * (h - 1);
        int indexCount = quadCount * 6;

        float[] vertices = new float[vertexCount * 8];
        int[] indices = new int[indexCount];

        float cellSizeX = 1.0f;
        float cellSizeZ = 1.0f;
        float offsetX = -(w - 1) * cellSizeX * 0.5f;
        float offsetZ = -(h - 1) * cellSizeZ * 0.5f;

        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                int vi = (z * w + x);
                int vi8 = vi * 8;
                float depth = currentDepthData[z * w + x];
                float px = offsetX + x * cellSizeX;
                float py = depth * HEIGHT_SCALE;
                float pz = offsetZ + z * cellSizeZ;

                vertices[vi8] = px;
                vertices[vi8 + 1] = py;
                vertices[vi8 + 2] = pz;
                vertices[vi8 + 3] = (float) x / (w - 1);
                vertices[vi8 + 4] = (float) z / (h - 1);

                float nx = 0, ny = 1, nz = 0;
                if (x > 0 && x < w - 1 && z > 0 && z < h - 1) {
                    float hL = currentDepthData[z * w + (x - 1)] * HEIGHT_SCALE;
                    float hR = currentDepthData[z * w + (x + 1)] * HEIGHT_SCALE;
                    float hD = currentDepthData[(z - 1) * w + x] * HEIGHT_SCALE;
                    float hU = currentDepthData[(z + 1) * w + x] * HEIGHT_SCALE;
                    nx = (hL - hR) * 0.5f;
                    nz = (hD - hU) * 0.5f;
                    ny = 1.0f;
                }
                Vector3f n = new Vector3f(nx, ny, nz).normalize();
                vertices[vi8 + 5] = n.x;
                vertices[vi8 + 6] = n.y;
                vertices[vi8 + 7] = n.z;
            }
        }

        int idx = 0;
        for (int z = 0; z < h - 1; z++) {
            for (int x = 0; x < w - 1; x++) {
                int tl = z * w + x;
                int tr = z * w + (x + 1);
                int bl = (z + 1) * w + x;
                int br = (z + 1) * w + (x + 1);

                indices[idx++] = tl;
                indices[idx++] = bl;
                indices[idx++] = tr;
                indices[idx++] = tr;
                indices[idx++] = bl;
                indices[idx++] = br;
            }
        }

        VertexLayout layout = new VertexLayout()
                .add(0, 3)
                .add(1, 2)
                .add(2, 3);
        MeshData data = new MeshData(vertices, indices, layout);

        terrainMesh = foolsEngine.serviceFactory.getMesh();
        terrainMesh.upload(data);
        System.out.printf("Terrain mesh: %d vertices, %d triangles%n", vertexCount, indices.length / 3);
    }

    private void nextDepthMap() {
        if (depthMapFiles.isEmpty()) return;
        currentIndex = (currentIndex + 1) % depthMapFiles.size();
        loadDepthMap(depthMapFiles.get(currentIndex));
        buildTerrainMesh();
    }

    private void prevDepthMap() {
        if (depthMapFiles.isEmpty()) return;
        currentIndex = (currentIndex - 1 + depthMapFiles.size()) % depthMapFiles.size();
        loadDepthMap(depthMapFiles.get(currentIndex));
        buildTerrainMesh();
    }
}
