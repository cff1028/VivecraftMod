package org.vivecraft.client_vr.provider.openxr;

import org.lwjgl.PointerBuffer;
import org.lwjgl.openxr.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.vivecraft.client_vr.ClientDataHolderVR;
import org.vivecraft.client_vr.VRState;
import org.vivecraft.client_vr.settings.VRSettings;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.lwjgl.openxr.KHRVulkanEnable2.*;
import static org.lwjgl.openxr.XR10.*;
import static org.lwjgl.vulkan.VK10.*;

public final class QuestVulkanContext {
    private static final boolean REQUESTED = "1".equals(System.getenv("FCL_VR_VULKAN"));
    private static final ThreadLocal<QuestVulkanContext> CREATING = new ThreadLocal<>();
    private static QuestVulkanContext game;
    private static long flatDevice;
    private static volatile boolean immersive;
    private static boolean resourcesReady;
    private static boolean reloading;

    private final QuestVulkanRequirements requirements;
    private String restartReason;
    private boolean invalid;
    private XrInstance xr;
    private long systemId;
    private long vkInstance;
    private long physical;
    private long vkDevice;
    private VkDevice device;
    private final Map<Integer, Integer> queueCounts = new HashMap<>();
    private boolean bindingMatches;
    private int queueFamily;
    private int queueIndex;
    private int apiVersion;
    private long loader;

    private QuestVulkanContext(boolean deferred) {
        this.requirements = deferred ? new QuestVulkanRequirements() : null;
    }

    public boolean usesGameDevice() {
        return this.requirements != null;
    }

    public static Set<String> additionalExtensions(boolean instance, Predicate<String> supported) {
        QuestVulkanContext context = creating();
        if (context == null || !context.usesGameDevice()) return Set.of();
        VRSettings settings = ClientDataHolderVR.getInstance().vrSettings;
        return QuestVulkanRequirements.toEnable(instance,
            instance ? settings.questVulkanInstanceExtensions : settings.questVulkanDeviceExtensions, supported);
    }

    public static boolean requested() {
        return REQUESTED;
    }

    public static boolean immersive() {
        return immersive;
    }

    public static void onResourcesReady() {
        resourcesReady = true;
        if (REQUESTED) VRSettings.LOGGER.info("Vivecraft: initial game resources ready for Vulkan VR");
    }

    public static void setReloading(boolean active) {
        reloading = active;
    }

    public static boolean waitingForResources() {
        return REQUESTED && (!resourcesReady || reloading);
    }

    public static boolean needsRestartForVR() {
        return REQUESTED && (flatDevice != 0 || (game != null && game.restartReason != null));
    }

    public static boolean canEnter() {
        return game != null && game.device != null;
    }

    public static QuestVulkanContext creating() {
        return CREATING.get();
    }

    public static <T> T createGameDevice(Supplier<T> create) {
        if (!REQUESTED) return create.get();
        VRSettings settings = ClientDataHolderVR.getInstance().vrSettings;
        boolean direct = settings.vrEnabled && settings.rememberVr &&
            settings.stereoProviderPluginID == VRSettings.VRProvider.OPENXR;
        boolean deferred;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            deferred = DeviceCompat.Mobile.initializeApplicationLoader(stack);
        } catch (RuntimeException | LinkageError failure) {
            VRSettings.LOGGER.warn("Vivecraft: will retry OpenXR loader initialization when entering VR", failure);
            deferred = true;
        }
        VRSettings.LOGGER.info("Vivecraft: Quest Vulkan startup={} rememberedVR={}, requested driver settings " +
                "VULKAN_DRIVER_SYSTEM={} DRIVER_LIB={} VKSHIM_ENABLE={}",
            deferred ? "flat until VR is ready" : "legacy FCL", direct, System.getenv("VULKAN_DRIVER_SYSTEM"),
            System.getenv("DRIVER_LIB"), System.getenv("VKSHIM_ENABLE"));
        if (!deferred && !direct) return create.get();

        QuestVulkanContext context = new QuestVulkanContext(deferred);
        CREATING.set(context);
        try {
            T result = create.get();
            if (context.device == null) throw new IllegalStateException("Minecraft did not create the OpenXR Vulkan device");
            game = context;
            return result;
        } finally {
            CREATING.remove();
            if (game != context) context.destroyInstance();
        }
    }

    public int createInstance(VkInstanceCreateInfo info, VkAllocationCallbacks allocator, PointerBuffer out,
                              IntSupplier original) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.loader = VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr");
            if (this.loader == 0) throw new IllegalStateException("Minecraft Vulkan loader has no vkGetInstanceProcAddr");
            this.apiVersion = info.pApplicationInfo().apiVersion();
            if (this.usesGameDevice()) {
                int status = original.getAsInt();
                if (status == VK_SUCCESS) {
                    this.vkInstance = out.get(out.position());
                    this.requirements.record(true, info.ppEnabledExtensionNames());
                }
                return status;
            }
            this.openInstance(stack);
            IntBuffer vk = stack.callocInt(1);
            XrVulkanInstanceCreateInfoKHR create = XrVulkanInstanceCreateInfoKHR.calloc(stack).type$Default()
                .systemId(this.systemId).pfnGetInstanceProcAddr(this.loader).vulkanCreateInfo(info).vulkanAllocator(allocator);
            int result = xrCreateVulkanInstanceKHR(this.xr, create, out, vk);
            int status = createResult("xrCreateVulkanInstanceKHR", result, vk);
            if (status == VK_SUCCESS) this.vkInstance = out.get(out.position());
            return status;
        } catch (RuntimeException e) {
            VRSettings.LOGGER.error("Vivecraft: Quest Vulkan instance setup failed", e);
            return VK_ERROR_INITIALIZATION_FAILED;
        }
    }

    private void openInstance(MemoryStack stack) {
        immersive = true;
        String extension = this.usesGameDevice() ? KHRVulkanEnable.XR_KHR_VULKAN_ENABLE_EXTENSION_NAME :
            XR_KHR_VULKAN_ENABLE2_EXTENSION_NAME;
        this.xr = MCOpenXR.createInstance(new DeviceCompat.Mobile(), extension);
        LongBuffer id = stack.callocLong(1);
        MCOpenXR.checkResult(xrGetSystem(this.xr, XrSystemGetInfo.calloc(stack).type$Default()
            .formFactor(XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY), id), "xrGetSystem");
        this.systemId = id.get(0);
        VRSettings.LOGGER.info("Vivecraft: OpenXR owner=0x{} system={} graphics={}",
            Long.toHexString(this.xr.address()), this.systemId, extension);
        if (this.usesGameDevice()) {
            this.restartReason = this.requirements.validate(stack, this.xr, this.systemId, this.device, this.apiVersion,
                ClientDataHolderVR.getInstance().vrSettings);
            if (this.restartReason != null) {
                throw new IllegalStateException("Restart to enable the required Vulkan extensions: " + this.restartReason);
            }
            return;
        }
        XrGraphicsRequirementsVulkan2KHR range = XrGraphicsRequirementsVulkan2KHR.calloc(stack).type$Default();
        MCOpenXR.checkResult(xrGetVulkanGraphicsRequirements2KHR(this.xr, this.systemId, range),
            "xrGetVulkanGraphicsRequirements2KHR");
        int major = VK_API_VERSION_MAJOR(this.apiVersion);
        int minor = VK_API_VERSION_MINOR(this.apiVersion);
        long version = XR_MAKE_VERSION(major, minor, 0);
        VRSettings.LOGGER.info("Vivecraft: Quest Vulkan {}.{} loader=0x{} OpenXR supports {}.{} to {}.{}",
            major, minor, Long.toHexString(this.loader),
            XR_VERSION_MAJOR(range.minApiVersionSupported()), XR_VERSION_MINOR(range.minApiVersionSupported()),
            XR_VERSION_MAJOR(range.maxApiVersionSupported()), XR_VERSION_MINOR(range.maxApiVersionSupported()));
        if (version < range.minApiVersionSupported() || version > range.maxApiVersionSupported()) {
            throw new IllegalStateException("Minecraft Vulkan API version is outside the OpenXR runtime range");
        }
    }

    private long runtimePhysicalDevice(MemoryStack stack, VkInstance instance) {
        PointerBuffer out = stack.callocPointer(1);
        XrVulkanGraphicsDeviceGetInfoKHR query = XrVulkanGraphicsDeviceGetInfoKHR.calloc(stack).type$Default()
            .systemId(this.systemId).vulkanInstance(instance);
        MCOpenXR.checkResult(xrGetVulkanGraphicsDevice2KHR(this.xr, query, out), "xrGetVulkanGraphicsDevice2KHR");
        if (out.get(0) == 0) throw new IllegalStateException("OpenXR returned a null Vulkan physical device");
        return out.get(0);
    }

    public int enumerateDevices(VkInstance instance, IntBuffer count, PointerBuffer out) {
        if (instance.address() != this.vkInstance) throw new IllegalStateException("Unexpected game Vulkan instance");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (this.physical == 0) {
                this.physical = this.runtimePhysicalDevice(stack, instance);
                VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(new VkPhysicalDevice(this.physical, instance), props);
                VRSettings.LOGGER.info("Vivecraft: xrGetVulkanGraphicsDevice2KHR {} (0x{}), vendor=0x{}, driver={}",
                    props.deviceNameString(), Long.toHexString(this.physical), Integer.toHexString(props.vendorID()),
                    Integer.toUnsignedString(props.driverVersion()));
            }
            if (out != null && count.get(count.position()) == 0) return VK_INCOMPLETE;
            count.put(count.position(), 1);
            if (out != null) out.put(out.position(), this.physical);
            return VK_SUCCESS;
        } catch (RuntimeException e) {
            VRSettings.LOGGER.error("Vivecraft: OpenXR Vulkan GPU selection failed", e);
            return VK_ERROR_INITIALIZATION_FAILED;
        }
    }

    public int createDevice(VkPhysicalDevice selected, VkDeviceCreateInfo info, VkAllocationCallbacks allocator,
                            PointerBuffer out, IntSupplier original) {
        var queues = info.pQueueCreateInfos();
        for (int i = 0; i < queues.remaining(); i++) {
            var queue = queues.get(i);
            if (queue.flags() == 0) this.queueCounts.put(queue.queueFamilyIndex(), queue.queueCount());
        }
        if (this.usesGameDevice()) {
            int status = original.getAsInt();
            if (status == VK_SUCCESS) {
                this.physical = selected.address();
                this.vkDevice = out.get(out.position());
                this.requirements.record(false, info.ppEnabledExtensionNames());
            }
            return status;
        }
        if (selected.address() != this.physical || selected.getInstance().address() != this.vkInstance) {
            VRSettings.LOGGER.error("Vivecraft: Minecraft selected a different Vulkan GPU than OpenXR");
            return VK_ERROR_INITIALIZATION_FAILED;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer vk = stack.callocInt(1);
            XrVulkanDeviceCreateInfoKHR create = XrVulkanDeviceCreateInfoKHR.calloc(stack).type$Default()
                .systemId(this.systemId).pfnGetInstanceProcAddr(this.loader).vulkanPhysicalDevice(selected)
                .vulkanCreateInfo(info).vulkanAllocator(allocator);
            int result = xrCreateVulkanDeviceKHR(this.xr, create, out, vk);
            int status = createResult("xrCreateVulkanDeviceKHR", result, vk);
            if (status == VK_SUCCESS) this.vkDevice = out.get(out.position());
            return status;
        }
    }

    private static int createResult(String name, int xr, IntBuffer vk) {
        if (xr != XR_SUCCESS) {
            VRSettings.LOGGER.error("Vivecraft: {} XrResult={}, VkResult unavailable", name, xr);
            return VK_ERROR_INITIALIZATION_FAILED;
        }
        int status = vk.get(0);
        VRSettings.LOGGER.info("Vivecraft: {} XrResult={}, VkResult={}", name, xr, status);
        return status;
    }

    public static void gameDeviceReady(VkDevice device, int family, int index) {
        if (!REQUESTED) return;
        QuestVulkanContext context = creating();
        if (context == null) {
            flatDevice = device.address();
            return;
        }
        context.bindingMatches = device.address() == context.vkDevice && index >= 0 &&
            index < context.queueCounts.getOrDefault(family, 0) && device.getPhysicalDevice().address() == context.physical &&
            device.getPhysicalDevice().getInstance().address() == context.vkInstance;
        if (!context.bindingMatches) {
            if (!context.usesGameDevice()) throw new IllegalStateException("Unexpected OpenXR Vulkan device or queue");
            VRSettings.LOGGER.warn("Vivecraft: game Vulkan device or graphics queue {}:{} does not match creation parameters",
                family, index);
        }
        context.device = device;
        context.queueFamily = family;
        context.queueIndex = index;
        context.logDevice();
        flatDevice = 0;
    }

    private void logDevice() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var driver = VkPhysicalDeviceDriverProperties.calloc(stack).sType$Default();
            var properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(driver);
            VK11.vkGetPhysicalDeviceProperties2(this.device.getPhysicalDevice(), properties);
            VRSettings.LOGGER.info("Vivecraft: actual Vulkan GPU={} driverName={} driverID={} driverInfo={} " +
                    "driverVersion={} loader=0x{} vkInstance=0x{} physical=0x{} device=0x{} queue={}:{}",
                properties.properties().deviceNameString(), driver.driverNameString(), driver.driverID(),
                driver.driverInfoString(), Integer.toUnsignedString(properties.properties().driverVersion()),
                Long.toHexString(this.loader), Long.toHexString(this.vkInstance), Long.toHexString(this.physical),
                Long.toHexString(this.vkDevice), this.queueFamily, this.queueIndex);
        }
    }

    public static XrInstance claimInstance() {
        if (waitingForResources()) throw new IllegalStateException("Minecraft resources are still loading");
        if (!canEnter() || needsRestartForVR()) throw new IllegalStateException("Restart Minecraft to enter Vulkan VR");
        if (!game.bindingMatches) throw new IllegalStateException("Vulkan VR requires the game's original graphics queue and device");
        immersive = true;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (game.xr == null) {
                if (!game.usesGameDevice()) throw new IllegalStateException("The enable2 device lost its OpenXR owner");
                game.openInstance(stack);
            } else {
                // Discard stale events before session handles can be reused.
                var event = XrEventDataBuffer.calloc(stack);
                while (true) {
                    event.clear();
                    int result = xrPollEvent(game.xr, event.type$Default());
                    if (result == XR_EVENT_UNAVAILABLE) break;
                    MCOpenXR.checkResult(result, "xrPollEvent before re-entry");
                    if (event.type() == XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING) {
                        throw new IllegalStateException("OpenXR instance loss pending");
                    }
                }
            }
            VRSettings.LOGGER.info("Vivecraft: entering OpenXR owner=0x{} system={}",
                Long.toHexString(game.xr.address()), game.systemId);
            return game.xr;
        } catch (RuntimeException | Error failure) {
            game.discardOwner();
            throw failure;
        }
    }

    static long systemId(XrInstance instance) {
        if (game == null || game.xr != instance) throw new IllegalStateException("Unexpected OpenXR instance");
        return game.systemId;
    }

    public static XrGraphicsBindingVulkanKHR binding(MemoryStack stack, XrInstance instance, long systemId) {
        if (!canEnter() || !game.bindingMatches || game.xr != instance || game.systemId != systemId) {
            throw new IllegalStateException("OpenXR session does not match the Minecraft Vulkan device");
        }
        VkPhysicalDevice gpu = game.device.getPhysicalDevice();
        VRSettings.LOGGER.info("Vivecraft: xrCreateSession Vulkan vkInstance=0x{} GPU=0x{} device=0x{} queue={}:{}",
            Long.toHexString(game.vkInstance), Long.toHexString(game.physical), Long.toHexString(game.vkDevice),
            game.queueFamily, game.queueIndex);
        XrGraphicsBindingVulkanKHR graphics = game.usesGameDevice() ?
            XrGraphicsBindingVulkanKHR.calloc(stack).type$Default() : XrGraphicsBindingVulkan2KHR.calloc(stack).type$Default();
        return graphics.instance(gpu.getInstance())
            .physicalDevice(gpu).device(game.device).queueFamilyIndex(game.queueFamily).queueIndex(game.queueIndex);
    }

    static int createSession(MemoryStack stack, XrInstance owner, long system, PointerBuffer out) {
        var info = XrSessionCreateInfo.calloc(stack).type$Default().systemId(system)
            .next(binding(stack, owner, system).address());
        int result = xrCreateSession(owner, info, out);
        VRSettings.LOGGER.info("Vivecraft: xrCreateSession owner=0x{} system={} session=0x{} result={}",
            Long.toHexString(owner.address()), system, Long.toHexString(result == XR_SUCCESS ? out.get(0) : 0), result);
        if (result == XR_ERROR_INSTANCE_LOST || result == XR_ERROR_INITIALIZATION_FAILED ||
            result == XR_ERROR_GRAPHICS_DEVICE_INVALID || result == XR_ERROR_VALIDATION_FAILURE) {
            invalidate(owner);
        }
        return result;
    }

    static void invalidate(XrInstance instance) {
        if (game != null && game.xr == instance) game.invalid = true;
    }

    static void leave(XrInstance instance) {
        if (game == null || game.xr != instance) return;
        immersive = false;
        // Older FCL versions require xrDestroyInstance to restore the panel.
        if (game.invalid || !game.usesGameDevice()) game.discardOwner();
    }

    private void discardOwner() {
        this.destroyInstance();
        this.invalid = false;
        if (!this.usesGameDevice()) this.restartReason = "OpenXR Vulkan device owner closed";
    }

    public static void shutdown() {
        if (game != null) game.destroyInstance();
    }

    private void destroyInstance() {
        if (this.xr != null) {
            int result = xrDestroyInstance(this.xr);
            if (result != XR_SUCCESS) VRSettings.LOGGER.error("Vivecraft: xrDestroyInstance returned {}", result);
            VRSettings.LOGGER.info("Vivecraft: released OpenXR owner=0x{} result={}", Long.toHexString(this.xr.address()), result);
            this.xr = null;
        }
        this.systemId = 0;
        immersive = false;
    }

    public static void beforeDeviceClose(VkDevice device) {
        if (!REQUESTED) return;
        if (flatDevice == device.address()) flatDevice = 0;
        if (game == null || game.device.address() != device.address()) return;
        if (game.xr != null) {
            MCOpenXR owner = MCOpenXR.get();
            if (owner != null && owner.instance == game.xr) {
                if (ClientDataHolderVR.getInstance().vr == owner) VRState.destroyVR(false);
                else owner.destroy();
            }
            game.destroyInstance();
        }
        game = null;
    }
}
