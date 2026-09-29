package com.localmath.engine

import com.localmath.engine.Sym.add
import com.localmath.engine.Sym.depends
import com.localmath.engine.Sym.mul
import com.localmath.engine.Sym.neg
import com.localmath.engine.Sym.pow

/** One quantity in a formula. [letter] is used inside [Formula.equation]; [tex] is how it's shown. */
data class FVar(
    val letter: Char,
    val tex: String,
    val meaning: String,
    val unit: String = "",
    val default: String? = null
) {
    /** Plain-text name for labels, e.g. V_out, ω. */
    val label: String get() = tex.replace("\\omega", "ω").replace("\\tau", "τ").replace("\\rho", "ρ").replace("\\sigma", "σ")
        .replace("\\varepsilon", "ε").replace("\\lambda", "λ").replace("\\eta", "η").replace("\\Delta ", "Δ").replace("\\Delta", "Δ")
        .replace("\\Pi", "Π").replace("\\pi", "π").replace("\\beta", "β").replace("\\%", "%")
        .replace("\\text", "").replace("{", "").replace("}", "")
}

data class Formula(
    val id: String,
    val category: String,
    val name: String,
    val plain: String,          // for lists and search, e.g. "V = I·R"
    val equation: String,       // LocalMath syntax using the single letters
    val vars: List<FVar>,
    val note: String? = null
)

private fun v(letter: Char, tex: String, meaning: String, unit: String = "", default: String? = null) = FVar(letter, tex, meaning, unit, default)

/** Unit for amounts of money: shown with two decimals and no currency sign, so any currency works. */
const val MONEY = "money"

object Formulas {

    const val ENGINEERING = "Engineering"
    const val FINANCE = "Finance & economics"

    /** The Formulas screen has one tab per group; each group lists its categories in this order. */
    val GROUPS: Map<String, List<String>> = linkedMapOf(
        ENGINEERING to listOf("DC circuits", "AC & electronics", "Digital & IoT", "Mechanics", "Materials", "Thermal & fluids", "Maths"),
        FINANCE to listOf("Interest & growth", "Loans & annuities", "Investing", "Accounting", "Business & pricing", "Economics")
    )

    val CATEGORIES = GROUPS.values.flatten()

    fun groupOf(f: Formula): String = GROUPS.entries.first { f.category in it.value }.key

    private fun isFinance(f: Formula) = groupOf(f) == FINANCE

    val ALL: List<Formula> = listOf(
        // ---------------- DC circuits ----------------
        Formula("ohm", "DC circuits", "Ohm's law", "V = I·R", "V = I R",
            listOf(v('V', "V", "voltage", "V"), v('I', "I", "current", "A"), v('R', "R", "resistance", "Ω"))),
        Formula("p_vi", "DC circuits", "Power (V·I)", "P = V·I", "P = V I",
            listOf(v('P', "P", "power", "W"), v('V', "V", "voltage", "V"), v('I', "I", "current", "A"))),
        Formula("p_i2r", "DC circuits", "Power (I²R)", "P = I²·R", "P = I^2 R",
            listOf(v('P', "P", "power", "W"), v('I', "I", "current", "A"), v('R', "R", "resistance", "Ω"))),
        Formula("p_v2r", "DC circuits", "Power (V²/R)", "P = V²/R", "P = V^2/R",
            listOf(v('P', "P", "power", "W"), v('V', "V", "voltage", "V"), v('R', "R", "resistance", "Ω"))),
        Formula("series", "DC circuits", "Resistors in series", "R_T = R₁ + R₂ + R₃", "T = a + b + c",
            listOf(v('T', "R_T", "total resistance", "Ω"), v('a', "R_1", "resistor 1", "Ω"), v('b', "R_2", "resistor 2", "Ω"),
                v('c', "R_3", "resistor 3 (0 if none)", "Ω", "0"))),
        Formula("parallel", "DC circuits", "Two resistors in parallel", "R_T = R₁R₂/(R₁ + R₂)", "T = a b/(a + b)",
            listOf(v('T', "R_T", "total resistance", "Ω"), v('a', "R_1", "resistor 1", "Ω"), v('b', "R_2", "resistor 2", "Ω")),
            "Tip: on the main screen you can also type 10k ∥ 4.7k."),
        Formula("vdiv", "DC circuits", "Voltage divider", "V_out = V_in·R₂/(R₁ + R₂)", "o = i b/(a + b)",
            listOf(v('o', "V_{out}", "output voltage", "V"), v('i', "V_{in}", "input voltage", "V"),
                v('a', "R_1", "top resistor", "Ω"), v('b', "R_2", "bottom resistor", "Ω"))),
        Formula("idiv", "DC circuits", "Current divider", "I₁ = I_T·R₂/(R₁ + R₂)", "p = t b/(a + b)",
            listOf(v('p', "I_1", "current through R₁", "A"), v('t', "I_T", "total current", "A"),
                v('a', "R_1", "resistor 1", "Ω"), v('b', "R_2", "resistor 2", "Ω"))),
        Formula("led", "DC circuits", "LED series resistor", "R = (V_s − V_f)/I", "R = (s - f)/I",
            listOf(v('R', "R", "resistor", "Ω"), v('s', "V_s", "supply voltage", "V"), v('f', "V_f", "LED forward voltage", "V", "2"),
                v('I', "I", "LED current", "A", "0.02"))),
        Formula("charge", "DC circuits", "Charge", "Q = I·t", "Q = I t",
            listOf(v('Q', "Q", "charge", "C"), v('I', "I", "current", "A"), v('t', "t", "time", "s"))),
        Formula("energy", "DC circuits", "Electrical energy", "E = P·t", "E = P t",
            listOf(v('E', "E", "energy", "J"), v('P', "P", "power", "W"), v('t', "t", "time", "s"))),
        Formula("resistivity", "DC circuits", "Resistance of a wire", "R = ρL/A", "R = r L/A",
            listOf(v('R', "R", "resistance", "Ω"), v('r', "\\rho", "resistivity", "Ω·m", "1.68e-8"),
                v('L', "L", "length", "m"), v('A', "A", "cross-section area", "m²"))),

        // ---------------- AC & electronics ----------------
        Formula("freq", "AC & electronics", "Frequency and period", "f = 1/T", "f = 1/T",
            listOf(v('f', "f", "frequency", "Hz"), v('T', "T", "period", "s"))),
        Formula("omega", "AC & electronics", "Angular frequency", "ω = 2πf", "w = 2π f",
            listOf(v('w', "\\omega", "angular frequency", "rad/s"), v('f', "f", "frequency", "Hz"))),
        Formula("xl", "AC & electronics", "Inductive reactance", "X_L = 2πfL", "X = 2π f L",
            listOf(v('X', "X_L", "inductive reactance", "Ω"), v('f', "f", "frequency", "Hz"), v('L', "L", "inductance", "H"))),
        Formula("xc", "AC & electronics", "Capacitive reactance", "X_C = 1/(2πfC)", "X = 1/(2π f C)",
            listOf(v('X', "X_C", "capacitive reactance", "Ω"), v('f', "f", "frequency", "Hz"), v('C', "C", "capacitance", "F"))),
        Formula("resonance", "AC & electronics", "LC resonant frequency", "f = 1/(2π√(LC))", "f = 1/(2π√(L C))",
            listOf(v('f', "f", "resonant frequency", "Hz"), v('L', "L", "inductance", "H"), v('C', "C", "capacitance", "F"))),
        Formula("impedance", "AC & electronics", "Impedance (series R and X)", "Z = √(R² + X²)", "Z = √(R^2 + X^2)",
            listOf(v('Z', "Z", "impedance", "Ω"), v('R', "R", "resistance", "Ω"), v('X', "X", "net reactance", "Ω"))),
        Formula("tau", "AC & electronics", "RC time constant", "τ = RC", "t = R C",
            listOf(v('t', "\\tau", "time constant", "s"), v('R', "R", "resistance", "Ω"), v('C', "C", "capacitance", "F"))),
        Formula("rc_charge", "AC & electronics", "Capacitor charging", "V_C = V_s(1 − e^(−t/RC))", "V = s (1 - e^(-t/(R C)))",
            listOf(v('V', "V_C", "capacitor voltage", "V"), v('s', "V_s", "supply voltage", "V"), v('t', "t", "time", "s"),
                v('R', "R", "resistance", "Ω"), v('C', "C", "capacitance", "F"))),
        Formula("rc_discharge", "AC & electronics", "Capacitor discharging", "V_C = V₀·e^(−t/RC)", "V = a e^(-t/(R C))",
            listOf(v('V', "V_C", "capacitor voltage", "V"), v('a', "V_0", "starting voltage", "V"), v('t', "t", "time", "s"),
                v('R', "R", "resistance", "Ω"), v('C', "C", "capacitance", "F"))),
        Formula("rms", "AC & electronics", "RMS voltage (sine)", "V_rms = V_p/√2", "r = p/√(2)",
            listOf(v('r', "V_{rms}", "RMS voltage", "V"), v('p', "V_p", "peak voltage", "V"))),
        Formula("q_cv", "AC & electronics", "Capacitor charge", "Q = C·V", "Q = C V",
            listOf(v('Q', "Q", "charge", "C"), v('C', "C", "capacitance", "F"), v('V', "V", "voltage", "V"))),
        Formula("e_cap", "AC & electronics", "Energy in a capacitor", "E = ½CV²", "E = C V^2/2",
            listOf(v('E', "E", "energy", "J"), v('C', "C", "capacitance", "F"), v('V', "V", "voltage", "V"))),
        Formula("e_ind", "AC & electronics", "Energy in an inductor", "E = ½LI²", "E = L I^2/2",
            listOf(v('E', "E", "energy", "J"), v('L', "L", "inductance", "H"), v('I', "I", "current", "A"))),
        Formula("gain_v", "AC & electronics", "Voltage gain in dB", "G = 20·log(V_out/V_in)", "G = 20 log(o/i)",
            listOf(v('G', "G", "gain", "dB"), v('o', "V_{out}", "output voltage", "V"), v('i', "V_{in}", "input voltage", "V"))),
        Formula("gain_p", "AC & electronics", "Power gain in dB", "G = 10·log(P_out/P_in)", "G = 10 log(o/i)",
            listOf(v('G', "G", "gain", "dB"), v('o', "P_{out}", "output power", "W"), v('i', "P_{in}", "input power", "W"))),
        Formula("transformer", "AC & electronics", "Transformer turns ratio", "V_s/V_p = N_s/N_p", "s/p = n/N",
            listOf(v('s', "V_s", "secondary voltage", "V"), v('p', "V_p", "primary voltage", "V"),
                v('n', "N_s", "secondary turns"), v('N', "N_p", "primary turns"))),
        Formula("cutoff", "AC & electronics", "RC filter cut-off frequency", "f_c = 1/(2πRC)", "f = 1/(2π R C)",
            listOf(v('f', "f_c", "cut-off frequency", "Hz"), v('R', "R", "resistance", "Ω"), v('C', "C", "capacitance", "F"))),

        // ---------------- Digital & IoT ----------------
        Formula("adc_step", "Digital & IoT", "ADC resolution (step size)", "Δ = V_ref/2ⁿ", "s = r/2^n",
            listOf(v('s', "\\Delta V", "smallest step", "V"), v('r', "V_{ref}", "reference voltage", "V", "3.3"), v('n', "n", "number of bits", "bit", "12"))),
        Formula("adc_volt", "Digital & IoT", "ADC reading to voltage", "V = D·V_ref/(2ⁿ − 1)", "V = D r/(2^n - 1)",
            listOf(v('V', "V", "input voltage", "V"), v('D', "D", "ADC reading"), v('r', "V_{ref}", "reference voltage", "V", "3.3"),
                v('n', "n", "number of bits", "bit", "10"))),
        Formula("nyquist", "Digital & IoT", "Nyquist sampling rate", "f_s = 2·f_max", "s = 2 f",
            listOf(v('s', "f_s", "minimum sampling rate", "Hz"), v('f', "f_{max}", "highest signal frequency", "Hz"))),
        Formula("wave", "Digital & IoT", "Wave speed", "v = f·λ", "v = f L",
            listOf(v('v', "v", "wave speed", "m/s", "299792458"), v('f', "f", "frequency", "Hz"), v('L', "\\lambda", "wavelength", "m"))),
        Formula("ultrasonic", "Digital & IoT", "Ultrasonic sensor distance", "d = v·t/2", "d = v t/2",
            listOf(v('d', "d", "distance", "m"), v('v', "v", "speed of sound", "m/s", "343"), v('t', "t", "echo time", "s")),
            "The pulse travels there and back, so the time is halved."),
        Formula("sound", "Digital & IoT", "Speed of sound in air", "v = 331 + 0.6·T", "v = 331 + 0.6 T",
            listOf(v('v', "v", "speed of sound", "m/s"), v('T', "T", "air temperature", "°C"))),
        Formula("duty", "Digital & IoT", "Duty cycle", "D = t_on/T × 100", "D = 100 h/T",
            listOf(v('D', "D", "duty cycle", "%"), v('h', "t_{on}", "high time", "s"), v('T', "T", "period", "s"))),
        Formula("pwm", "Digital & IoT", "PWM average voltage", "V_avg = D/100 × V", "a = D s/100",
            listOf(v('a', "V_{avg}", "average voltage", "V"), v('D', "D", "duty cycle", "%"), v('s', "V", "high voltage", "V", "5"))),
        Formula("transfer", "Digital & IoT", "Transfer time", "t = size/rate", "t = S/B",
            listOf(v('t', "t", "time", "s"), v('S', "S", "data size", "bit"), v('B', "B", "bit rate", "bit/s"))),

        // ---------------- Mechanics ----------------
        Formula("suvat1", "Mechanics", "v = u + at", "v = u + a·t", "v = u + a t",
            listOf(v('v', "v", "final velocity", "m/s"), v('u', "u", "initial velocity", "m/s"), v('a', "a", "acceleration", "m/s²"), v('t', "t", "time", "s"))),
        Formula("suvat2", "Mechanics", "s = ut + ½at²", "s = u·t + ½a·t²", "s = u t + a t^2/2",
            listOf(v('s', "s", "displacement", "m"), v('u', "u", "initial velocity", "m/s"), v('t', "t", "time", "s"), v('a', "a", "acceleration", "m/s²"))),
        Formula("suvat3", "Mechanics", "v² = u² + 2as", "v² = u² + 2a·s", "v^2 = u^2 + 2 a s",
            listOf(v('v', "v", "final velocity", "m/s"), v('u', "u", "initial velocity", "m/s"), v('a', "a", "acceleration", "m/s²"), v('s', "s", "displacement", "m"))),
        Formula("suvat4", "Mechanics", "s = (u + v)t/2", "s = (u + v)·t/2", "s = (u + v) t/2",
            listOf(v('s', "s", "displacement", "m"), v('u', "u", "initial velocity", "m/s"), v('v', "v", "final velocity", "m/s"), v('t', "t", "time", "s"))),
        Formula("newton2", "Mechanics", "Newton's second law", "F = m·a", "F = m a",
            listOf(v('F', "F", "force", "N"), v('m', "m", "mass", "kg"), v('a', "a", "acceleration", "m/s²"))),
        Formula("weight", "Mechanics", "Weight", "W = m·g", "W = m g",
            listOf(v('W', "W", "weight", "N"), v('m', "m", "mass", "kg"), v('g', "g", "gravity", "m/s²", "9.81"))),
        Formula("work", "Mechanics", "Work done", "W = F·d", "W = F d",
            listOf(v('W', "W", "work", "J"), v('F', "F", "force", "N"), v('d', "d", "distance", "m"))),
        Formula("mpower", "Mechanics", "Mechanical power", "P = W/t", "P = W/t",
            listOf(v('P', "P", "power", "W"), v('W', "W", "work", "J"), v('t', "t", "time", "s"))),
        Formula("ke", "Mechanics", "Kinetic energy", "KE = ½mv²", "K = m v^2/2",
            listOf(v('K', "E_k", "kinetic energy", "J"), v('m', "m", "mass", "kg"), v('v', "v", "speed", "m/s"))),
        Formula("pe", "Mechanics", "Potential energy", "PE = m·g·h", "U = m g h",
            listOf(v('U', "E_p", "potential energy", "J"), v('m', "m", "mass", "kg"), v('g', "g", "gravity", "m/s²", "9.81"), v('h', "h", "height", "m"))),
        Formula("momentum", "Mechanics", "Momentum", "p = m·v", "p = m v",
            listOf(v('p', "p", "momentum", "kg·m/s"), v('m', "m", "mass", "kg"), v('v', "v", "velocity", "m/s"))),
        Formula("torque", "Mechanics", "Torque", "τ = F·r", "T = F r",
            listOf(v('T', "\\tau", "torque", "N·m"), v('F', "F", "force", "N"), v('r', "r", "lever arm", "m"))),
        Formula("pressure", "Mechanics", "Pressure", "P = F/A", "P = F/A",
            listOf(v('P', "P", "pressure", "Pa"), v('F', "F", "force", "N"), v('A', "A", "area", "m²"))),
        Formula("circular", "Mechanics", "Speed in a circle", "v = ω·r", "v = w r",
            listOf(v('v', "v", "speed", "m/s"), v('w', "\\omega", "angular velocity", "rad/s"), v('r', "r", "radius", "m"))),
        Formula("centripetal", "Mechanics", "Centripetal force", "F = m·v²/r", "F = m v^2/r",
            listOf(v('F', "F", "force", "N"), v('m', "m", "mass", "kg"), v('v', "v", "speed", "m/s"), v('r', "r", "radius", "m"))),

        // ---------------- Materials ----------------
        Formula("stress", "Materials", "Stress", "σ = F/A", "s = F/A",
            listOf(v('s', "\\sigma", "stress", "Pa"), v('F', "F", "force", "N"), v('A', "A", "area", "m²"))),
        Formula("strain", "Materials", "Strain", "ε = ΔL/L", "x = d/L",
            listOf(v('x', "\\varepsilon", "strain"), v('d', "\\Delta L", "extension", "m"), v('L', "L", "original length", "m"))),
        Formula("young", "Materials", "Young's modulus", "E = σ/ε", "Y = s/x",
            listOf(v('Y', "E", "Young's modulus", "Pa"), v('s', "\\sigma", "stress", "Pa"), v('x', "\\varepsilon", "strain"))),
        Formula("density", "Materials", "Density", "ρ = m/V", "r = m/V",
            listOf(v('r', "\\rho", "density", "kg/m³"), v('m', "m", "mass", "kg"), v('V', "V", "volume", "m³"))),

        // ---------------- Thermal & fluids ----------------
        Formula("heat", "Thermal & fluids", "Heat energy", "Q = m·c·ΔT", "Q = m c T",
            listOf(v('Q', "Q", "heat", "J"), v('m', "m", "mass", "kg"), v('c', "c", "specific heat", "J/(kg·K)", "4186"), v('T', "\\Delta T", "temperature change", "K"))),
        Formula("gas", "Thermal & fluids", "Ideal gas law", "PV = nRT", "P V = n R T",
            listOf(v('P', "P", "pressure", "Pa"), v('V', "V", "volume", "m³"), v('n', "n", "amount", "mol"),
                v('R', "R", "gas constant", "J/(mol·K)", "8.314"), v('T', "T", "temperature", "K"))),
        Formula("flow", "Thermal & fluids", "Flow rate", "Q = A·v", "Q = A v",
            listOf(v('Q', "Q", "flow rate", "m³/s"), v('A', "A", "pipe area", "m²"), v('v', "v", "flow speed", "m/s"))),
        Formula("hydro", "Thermal & fluids", "Pressure in a liquid", "P = ρ·g·h", "P = r g h",
            listOf(v('P', "P", "pressure", "Pa"), v('r', "\\rho", "density", "kg/m³", "1000"), v('g', "g", "gravity", "m/s²", "9.81"), v('h', "h", "depth", "m"))),
        Formula("eff", "Thermal & fluids", "Efficiency", "η = out/in × 100", "n = 100 o/i",
            listOf(v('n', "\\eta", "efficiency", "%"), v('o', "P_{out}", "useful output", "W"), v('i', "P_{in}", "input", "W"))),
        Formula("c_to_k", "Thermal & fluids", "Celsius to kelvin", "T_K = T_C + 273.15", "K = C + 273.15",
            listOf(v('K', "T_K", "temperature", "K"), v('C', "T_C", "temperature", "°C"))),

        // ---------------- Maths ----------------
        Formula("pythag", "Maths", "Pythagoras", "c² = a² + b²", "c^2 = a^2 + b^2",
            listOf(v('c', "c", "hypotenuse"), v('a', "a", "side a"), v('b', "b", "side b"))),
        Formula("circle_a", "Maths", "Area of a circle", "A = πr²", "A = π r^2",
            listOf(v('A', "A", "area", "m²"), v('r', "r", "radius", "m"))),
        Formula("circle_c", "Maths", "Circumference", "C = 2πr", "C = 2π r",
            listOf(v('C', "C", "circumference", "m"), v('r', "r", "radius", "m"))),
        Formula("sphere", "Maths", "Volume of a sphere", "V = 4/3·πr³", "V = 4π r^3/3",
            listOf(v('V', "V", "volume", "m³"), v('r', "r", "radius", "m"))),
        Formula("cylinder", "Maths", "Volume of a cylinder", "V = πr²h", "V = π r^2 h",
            listOf(v('V', "V", "volume", "m³"), v('r', "r", "radius", "m"), v('h', "h", "height", "m"))),
        Formula("triangle", "Maths", "Area of a triangle", "A = ½·b·h", "A = b h/2",
            listOf(v('A', "A", "area", "m²"), v('b', "b", "base", "m"), v('h', "h", "height", "m"))),
        Formula("pct", "Maths", "Percentage change", "Δ% = (new − old)/old × 100", "p = 100 (n - o)/o",
            listOf(v('p', "\\Delta\\%", "percentage change", "%"), v('n', "\\text{new}", "new value"), v('o', "\\text{old}", "old value"))),

        // ================= Finance & economics =================
        // Rates are typed in percent (5 means 5%), so the equations divide by 100.

        // ---------------- Interest & growth ----------------
        Formula("simple_int", "Interest & growth", "Simple interest", "I = P·r·t", "I = P r t/100",
            listOf(v('I', "I", "interest earned", MONEY), v('P', "P", "principal (starting amount)", MONEY),
                v('r', "r", "interest rate per year", "%"), v('t', "t", "time", "years"))),
        Formula("simple_amt", "Interest & growth", "Simple interest: final amount", "A = P(1 + r·t)", "A = P (1 + r t/100)",
            listOf(v('A', "A", "final amount", MONEY), v('P', "P", "principal", MONEY),
                v('r', "r", "interest rate per year", "%"), v('t', "t", "time", "years"))),
        Formula("compound", "Interest & growth", "Compound interest", "A = P(1 + r/n)^(n·t)", "A = P (1 + r/(100 n))^(n t)",
            listOf(v('A', "A", "final amount", MONEY), v('P', "P", "principal", MONEY), v('r', "r", "interest rate per year", "%"),
                v('n', "n", "times compounded per year", "", "12"), v('t', "t", "time", "years")),
            "n = 1 yearly, 4 quarterly, 12 monthly, 365 daily."),
        Formula("continuous", "Interest & growth", "Continuous compounding", "A = P·e^(r·t)", "A = P e^(r t/100)",
            listOf(v('A', "A", "final amount", MONEY), v('P', "P", "principal", MONEY),
                v('r', "r", "interest rate per year", "%"), v('t', "t", "time", "years"))),
        Formula("ear", "Interest & growth", "Effective annual rate (EAR / APY)", "EAR = (1 + r/n)ⁿ − 1", "E = 100 ((1 + r/(100 n))^n - 1)",
            listOf(v('E', "\\text{EAR}", "effective annual rate", "%"), v('r', "r", "nominal (quoted) rate per year", "%"),
                v('n', "n", "times compounded per year", "", "12"))),
        Formula("pv", "Interest & growth", "Present value", "PV = FV/(1 + r)^t", "P = F/(1 + r/100)^t",
            listOf(v('P', "PV", "present value", MONEY), v('F', "FV", "future value", MONEY),
                v('r', "r", "discount rate per period", "%"), v('t', "t", "number of periods"))),
        Formula("cagr", "Interest & growth", "Compound annual growth rate (CAGR)", "CAGR = (end/start)^(1/t) − 1", "g = 100 ((E/B)^(1/t) - 1)",
            listOf(v('g', "\\text{CAGR}", "growth rate per year", "%"), v('E', "V_{end}", "ending value", MONEY),
                v('B', "V_{start}", "starting value", MONEY), v('t', "t", "time", "years"))),
        Formula("doubling", "Interest & growth", "Doubling time", "t = ln 2/ln(1 + r)", "t = ln(2)/ln(1 + r/100)",
            listOf(v('t', "t", "time to double", "years"), v('r', "r", "growth rate per year", "%"))),
        Formula("rule72", "Interest & growth", "Rule of 72 (quick estimate)", "t ≈ 72/r", "t = 72/r",
            listOf(v('t', "t", "time to double (approx.)", "years"), v('r', "r", "growth rate per year", "%")),
            "A mental shortcut; use Doubling time for the exact answer."),
        Formula("fisher", "Interest & growth", "Real interest rate (Fisher equation)", "1 + nominal = (1 + real)(1 + inflation)",
            "1 + n/100 = (1 + r/100) (1 + f/100)",
            listOf(v('n', "i_{nom}", "nominal interest rate", "%"), v('r', "r_{real}", "real interest rate", "%"),
                v('f', "\\pi", "inflation rate", "%"))),

        // ---------------- Loans & annuities ----------------
        Formula("loan_pmt", "Loans & annuities", "Loan / mortgage monthly payment", "M = P·i/(1 − (1 + i)^(−N)), i = r/12",
            "M = P (r/1200)/(1 - (1 + r/1200)^(-N))",
            listOf(v('M', "M", "monthly payment", MONEY), v('P', "P", "amount borrowed", MONEY),
                v('r', "r", "interest rate per year", "%"), v('N', "N", "number of monthly payments", "months")),
            "A 30-year mortgage has N = 360."),
        Formula("loan_int", "Loans & annuities", "Total interest on a loan", "I = M·N − P", "I = M N - P",
            listOf(v('I', "I", "total interest paid", MONEY), v('M', "M", "payment each period", MONEY),
                v('N', "N", "number of payments"), v('P', "P", "amount borrowed", MONEY))),
        Formula("loan_bal", "Loans & annuities", "Loan balance after k payments", "B = P(1 + i)^k − M((1 + i)^k − 1)/i, i = r/12",
            "B = P (1 + r/1200)^k - M ((1 + r/1200)^k - 1)/(r/1200)",
            listOf(v('B', "B", "balance still owed", MONEY), v('P', "P", "amount borrowed", MONEY), v('r', "r", "interest rate per year", "%"),
                v('M', "M", "monthly payment", MONEY), v('k', "k", "payments made so far", "months"))),
        Formula("fv_annuity", "Loans & annuities", "Future value of regular savings", "FV = PMT·((1 + i)^N − 1)/i",
            "F = M ((1 + r/100)^N - 1)/(r/100)",
            listOf(v('F', "FV", "future value", MONEY), v('M', "PMT", "payment each period", MONEY),
                v('r', "i", "interest rate per period", "%"), v('N', "N", "number of payments")),
            "Payments at the end of each period (ordinary annuity)."),
        Formula("pv_annuity", "Loans & annuities", "Present value of an annuity", "PV = PMT·(1 − (1 + i)^(−N))/i",
            "P = M (1 - (1 + r/100)^(-N))/(r/100)",
            listOf(v('P', "PV", "present value", MONEY), v('M', "PMT", "payment each period", MONEY),
                v('r', "i", "interest rate per period", "%"), v('N', "N", "number of payments"))),
        Formula("perpetuity", "Loans & annuities", "Perpetuity", "PV = PMT/i", "P = M/(r/100)",
            listOf(v('P', "PV", "present value", MONEY), v('M', "PMT", "payment each period (forever)", MONEY),
                v('r', "i", "interest rate per period", "%"))),

        // ---------------- Investing ----------------
        Formula("roi", "Investing", "Return on investment (ROI)", "ROI = (gain − cost)/cost × 100", "R = 100 (G - C)/C",
            listOf(v('R', "\\text{ROI}", "return on investment", "%"), v('G', "G", "final value", MONEY), v('C', "C", "cost", MONEY))),
        Formula("hpr", "Investing", "Holding period return", "HPR = (end − start + income)/start × 100", "H = 100 (E - B + D)/B",
            listOf(v('H', "\\text{HPR}", "total return", "%"), v('E', "V_{end}", "ending value", MONEY),
                v('B', "V_{start}", "starting value", MONEY), v('D', "D", "dividends / income received", MONEY, "0"))),
        Formula("div_yield", "Investing", "Dividend yield", "yield = dividend/price × 100", "y = 100 D/P",
            listOf(v('y', "y", "dividend yield", "%"), v('D', "D", "annual dividend per share", MONEY), v('P', "P", "share price", MONEY))),
        Formula("eps", "Investing", "Earnings per share (EPS)", "EPS = (net income − preferred dividends)/shares", "E = (N - D)/S",
            listOf(v('E', "\\text{EPS}", "earnings per share", MONEY), v('N', "N", "net income", MONEY),
                v('D', "D", "preferred dividends", MONEY, "0"), v('S', "S", "shares outstanding"))),
        Formula("pe", "Investing", "Price-to-earnings (P/E) ratio", "P/E = price/EPS", "R = P/E",
            listOf(v('R', "P/E", "price-to-earnings ratio"), v('P', "P", "share price", MONEY), v('E', "\\text{EPS}", "earnings per share", MONEY))),
        Formula("gordon", "Investing", "Dividend discount model (Gordon growth)", "P = D₁/(k − g)", "P = D/((k - g)/100)",
            listOf(v('P', "P", "fair share price", MONEY), v('D', "D_1", "next year's dividend", MONEY),
                v('k', "k", "required return", "%"), v('g', "g", "dividend growth rate", "%")),
            "Only makes sense when k is bigger than g."),
        Formula("capm", "Investing", "CAPM expected return", "E(R) = R_f + β(R_m − R_f)", "E = f + b (m - f)",
            listOf(v('E', "E(R)", "expected return", "%"), v('f', "R_f", "risk-free rate", "%"),
                v('b', "\\beta", "beta"), v('m', "R_m", "market return", "%"))),

        // ---------------- Accounting ----------------
        Formula("acct_eq", "Accounting", "Accounting equation", "Assets = Liabilities + Equity", "A = L + Q",
            listOf(v('A', "A", "assets", MONEY), v('L', "L", "liabilities", MONEY), v('Q', "E", "owner's equity", MONEY))),
        Formula("gross_margin", "Accounting", "Gross profit margin", "GM = (revenue − COGS)/revenue × 100", "m = 100 (R - C)/R",
            listOf(v('m', "\\text{GM}", "gross margin", "%"), v('R', "R", "revenue (sales)", MONEY), v('C', "\\text{COGS}", "cost of goods sold", MONEY))),
        Formula("net_margin", "Accounting", "Net profit margin", "NM = net income/revenue × 100", "m = 100 N/R",
            listOf(v('m', "\\text{NM}", "net margin", "%"), v('N', "N", "net income", MONEY), v('R', "R", "revenue", MONEY))),
        Formula("sl_dep", "Accounting", "Straight-line depreciation", "D = (cost − salvage)/life", "D = (C - S)/L",
            listOf(v('D', "D", "depreciation per year", MONEY), v('C', "C", "cost of the asset", MONEY),
                v('S', "S", "salvage (residual) value", MONEY, "0"), v('L', "L", "useful life", "years"))),
        Formula("db_dep", "Accounting", "Declining-balance book value", "BV = cost × (1 − d)^t", "B = C (1 - d/100)^t",
            listOf(v('B', "BV", "book value", MONEY), v('C', "C", "original cost", MONEY),
                v('d', "d", "depreciation rate per year", "%"), v('t', "t", "age", "years"))),
        Formula("current_ratio", "Accounting", "Current ratio", "CR = current assets/current liabilities", "R = A/L",
            listOf(v('R', "\\text{CR}", "current ratio"), v('A', "A", "current assets", MONEY), v('L', "L", "current liabilities", MONEY))),
        Formula("quick_ratio", "Accounting", "Quick (acid-test) ratio", "QR = (current assets − inventory)/current liabilities", "R = (A - I)/L",
            listOf(v('R', "\\text{QR}", "quick ratio"), v('A', "A", "current assets", MONEY), v('I', "I", "inventory", MONEY),
                v('L', "L", "current liabilities", MONEY))),
        Formula("de_ratio", "Accounting", "Debt-to-equity ratio", "D/E = total liabilities/equity", "R = L/Q",
            listOf(v('R', "D/E", "debt-to-equity ratio"), v('L', "L", "total liabilities", MONEY), v('Q', "E", "shareholders' equity", MONEY))),
        Formula("roa", "Accounting", "Return on assets (ROA)", "ROA = net income/total assets × 100", "R = 100 N/A",
            listOf(v('R', "\\text{ROA}", "return on assets", "%"), v('N', "N", "net income", MONEY), v('A', "A", "total assets", MONEY))),
        Formula("roe", "Accounting", "Return on equity (ROE)", "ROE = net income/equity × 100", "R = 100 N/Q",
            listOf(v('R', "\\text{ROE}", "return on equity", "%"), v('N', "N", "net income", MONEY), v('Q', "E", "shareholders' equity", MONEY))),
        Formula("inv_turn", "Accounting", "Inventory turnover", "turnover = COGS/average inventory", "T = C/I",
            listOf(v('T', "T", "inventory turnover (times per year)"), v('C', "\\text{COGS}", "cost of goods sold", MONEY),
                v('I', "I", "average inventory", MONEY))),
        Formula("dso", "Accounting", "Days sales outstanding (DSO)", "DSO = receivables/credit sales × 365", "D = 365 A/S",
            listOf(v('D', "\\text{DSO}", "average days to get paid", "days"), v('A', "AR", "accounts receivable", MONEY),
                v('S', "S", "credit sales for the year", MONEY))),

        // ---------------- Business & pricing ----------------
        Formula("revenue", "Business & pricing", "Total revenue", "R = P·Q", "R = P Q",
            listOf(v('R', "R", "revenue", MONEY), v('P', "P", "price per unit", MONEY), v('Q', "Q", "units sold"))),
        Formula("total_cost", "Business & pricing", "Total cost", "TC = FC + VC·Q", "T = F + V Q",
            listOf(v('T', "TC", "total cost", MONEY), v('F', "FC", "fixed costs", MONEY), v('V', "VC", "variable cost per unit", MONEY),
                v('Q', "Q", "units made"))),
        Formula("profit", "Business & pricing", "Profit", "profit = revenue − cost", "p = R - C",
            listOf(v('p', "\\Pi", "profit", MONEY), v('R', "R", "revenue", MONEY), v('C', "C", "total cost", MONEY))),
        Formula("breakeven", "Business & pricing", "Break-even point", "Q = FC/(price − VC)", "Q = F/(P - V)",
            listOf(v('Q', "Q", "units to break even"), v('F', "FC", "fixed costs", MONEY),
                v('P', "P", "price per unit", MONEY), v('V', "VC", "variable cost per unit", MONEY))),
        Formula("cm_ratio", "Business & pricing", "Contribution margin ratio", "CM = (price − VC)/price × 100", "c = 100 (P - V)/P",
            listOf(v('c', "\\text{CM}", "contribution margin ratio", "%"), v('P', "P", "price per unit", MONEY),
                v('V', "VC", "variable cost per unit", MONEY))),
        Formula("markup", "Business & pricing", "Markup on cost", "markup = (price − cost)/cost × 100", "m = 100 (P - C)/C",
            listOf(v('m', "m", "markup", "%"), v('P', "P", "selling price", MONEY), v('C', "C", "cost", MONEY))),
        Formula("discount", "Business & pricing", "Price after a discount", "S = P × (1 − d)", "S = P (1 - d/100)",
            listOf(v('S', "S", "sale price", MONEY), v('P', "P", "original price", MONEY), v('d', "d", "discount", "%"))),
        Formula("tax", "Business & pricing", "Price including tax (VAT / GST / sales tax)", "T = P × (1 + t)", "T = P (1 + r/100)",
            listOf(v('T', "T", "price with tax", MONEY), v('P', "P", "price before tax", MONEY), v('r', "t", "tax rate", "%"))),

        // ---------------- Economics ----------------
        Formula("elast_mid", "Economics", "Price elasticity of demand (midpoint)", "E = (ΔQ/avg Q)/(ΔP/avg P)",
            "E = ((b - a)/(a + b))/((d - c)/(c + d))",
            listOf(v('E', "E_d", "price elasticity"), v('a', "Q_1", "old quantity"), v('b', "Q_2", "new quantity"),
                v('c', "P_1", "old price", MONEY), v('d', "P_2", "new price", MONEY)),
            "|E| > 1 is elastic, |E| < 1 is inelastic. Demand elasticity is usually negative."),
        Formula("elast", "Economics", "Elasticity from % changes", "E = %ΔQ/%ΔP", "E = q/p",
            listOf(v('E', "E", "elasticity"), v('q', "\\%\\Delta Q", "percentage change in quantity", "%"),
                v('p', "\\%\\Delta P", "percentage change in price", "%"))),
        Formula("gdp", "Economics", "GDP (expenditure approach)", "Y = C + I + G + (X − M)", "Y = C + I + G + X - M",
            listOf(v('Y', "Y", "GDP", MONEY), v('C', "C", "consumption", MONEY), v('I', "I", "investment", MONEY),
                v('G', "G", "government spending", MONEY), v('X', "X", "exports", MONEY), v('M', "M", "imports", MONEY))),
        Formula("real_gdp", "Economics", "Real GDP", "real GDP = nominal GDP/deflator × 100", "R = 100 N/D",
            listOf(v('R', "\\text{real}", "real GDP", MONEY), v('N', "\\text{nominal}", "nominal GDP", MONEY),
                v('D', "D", "GDP deflator (base year = 100)", "", "100"))),
        Formula("inflation", "Economics", "Inflation rate (from a price index)", "π = (CPI₂ − CPI₁)/CPI₁ × 100", "f = 100 (b - a)/a",
            listOf(v('f', "\\pi", "inflation rate", "%"), v('b', "\\text{CPI}_2", "price index now"), v('a', "\\text{CPI}_1", "price index before"))),
        Formula("unemployment", "Economics", "Unemployment rate", "u = unemployed/labour force × 100", "u = 100 U/L",
            listOf(v('u', "u", "unemployment rate", "%"), v('U', "U", "people unemployed"), v('L', "L", "labour force"))),
        Formula("multiplier", "Economics", "Spending multiplier", "k = 1/(1 − MPC)", "k = 1/(1 - c)",
            listOf(v('k', "k", "multiplier"), v('c', "\\text{MPC}", "marginal propensity to consume (0 to 1)"))),
        Formula("money_mult", "Economics", "Money multiplier", "m = 1/reserve ratio", "m = 100/r",
            listOf(v('m', "m", "money multiplier"), v('r', "r", "reserve requirement", "%"))),
        Formula("qtm", "Economics", "Quantity theory of money", "M·V = P·Y", "M V = P Y",
            listOf(v('M', "M", "money supply", MONEY), v('V', "V", "velocity of money"), v('P', "P", "price level"),
                v('Y', "Y", "real output"))),
        Formula("real_value", "Economics", "Value in today's money", "real = nominal/(1 + π)^t", "R = N/(1 + f/100)^t",
            listOf(v('R', "R", "value in today's money", MONEY), v('N', "N", "future amount", MONEY),
                v('f', "\\pi", "inflation rate per year", "%"), v('t', "t", "time", "years")))
    )

    fun byId(id: String) = ALL.firstOrNull { it.id == id }

    fun search(query: String): List<Formula> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return ALL
        return ALL.filter { f ->
            f.name.lowercase().contains(q) || f.plain.lowercase().contains(q) || f.category.lowercase().contains(q) ||
                f.vars.any { it.meaning.lowercase().contains(q) }
        }
    }

    // ---------------- display ----------------

    private fun priv(i: Int) = '\uE000' + i

    private fun parts(f: Formula): Pair<Expr, Expr> {
        val eq = Parser.parse(f.equation, imaginaryUnit = false) as? Input.Equation ?: throw MathError("Bad formula ${f.id}")
        fun map(e: Expr) = e.mapNodes { n ->
            if (n is Expr.Var) {
                val i = f.vars.indexOfFirst { it.letter == n.name }
                if (i < 0) throw MathError("Formula ${f.id} uses an unknown letter ${n.name}")
                Expr.Var(priv(i))
            } else null
        }
        return map(eq.left) to map(eq.right)
    }

    private fun named(f: Formula, s: String): String {
        var out = s
        f.vars.forEachIndexed { i, fv -> out = out.replace(priv(i).toString(), "{${upright(fv.tex)}}") }
        return out
    }

    /** Word subscripts (out, in, ref, rms) are labels, so set them upright: V_{out} -> V_{\\mathrm{out}}. */
    private fun upright(tex: String) = tex.replace(Regex("_\\{([A-Za-z]{2,})\\}"), "_{\\\\mathrm{$1}}")

    /** The formula in LaTeX, with its real symbols. */
    fun tex(f: Formula): String {
        val (l, r) = parts(f)
        return named(f, Tex.equation(l, r))
    }

    // ---------------- solving ----------------

    /**
     * Solves [f] for the variable with letter [unknown], using the typed [inputs] (by letter)
     * or each variable's default value.
     */
    fun solve(f: Formula, inputs: Map<Char, String>, unknown: Char): Solution {
        val ui = f.vars.indexOfFirst { it.letter == unknown }
        if (ui < 0) throw MathError("Pick what to solve for")
        val target = f.vars[ui]
        val u = priv(ui)
        val (le, re) = parts(f)
        val left = Sym.from(le)
        val right = Sym.from(re)

        val values = mutableMapOf<Char, S>()
        f.vars.forEachIndexed { i, fv ->
            if (i == ui) return@forEachIndexed
            val raw = inputs[fv.letter]?.takeIf { it.isNotBlank() } ?: fv.default
                ?: throw MathError("Enter a value for ${fv.label} (${fv.meaning})")
            values[priv(i)] = Eng.parseValue(raw) ?: throw MathError("Enter a value for ${fv.label}")
        }

        val steps = mutableListOf<Step>()
        steps += Step("Formula: ${f.name}", named(f, Tex.equation(le, re)),
            f.vars.joinToString(", ") { "\\(${it.tex}\\) = ${it.meaning}" + if (it.unit.isNotEmpty()) " (${it.unit})" else "" })
        steps += Step("Known values", f.vars.withIndex().filter { it.index != ui }.joinToString(",\\quad ") { (i, fv) ->
            "${fv.tex} = ${number(f, Sym.eval(values.getValue(priv(i)), emptyMap()), fv.unit)}"
        })

        val count = occurrences(left, u) + occurrences(right, u)
        val results: List<S>
        var numeric = false
        val notes = mutableListOf<String>()
        val iso = if (count == 1) {
            if (depends(left, u)) isolate(left, right, u, notes) else isolate(right, left, u, notes)
        } else null

        if (iso != null) {
            steps += Step("Rearrange for \\(${target.tex}\\)", named(f, "$u = ${Sym.latex(iso)}"), notes.firstOrNull())
            steps += Step("Substitute the values", named(f, "$u = ") + substituteTex(Sym.latex(iso), values) { number(f, it, "") })
            val r = substitute(iso, values)
            results = listOf(r)
        } else {
            // The unknown appears more than once: put the numbers in, then solve the equation.
            val g = add(substitute(left, values), neg(substitute(right, values)))
            val rf = RatFunc.fromS(g, u)
            val found = mutableListOf<S>()
            if (rf != null && !rf.num.isConstant) {
                val polySteps = mutableListOf<Step>()
                val out = PolyEquation(u, polySteps).solve(rf.num, Polynomial.constant(Rational.ZERO))
                steps += Step("Put the numbers in", named(f, "${Sym.latex(g)} = 0"),
                    "\\(${target.tex}\\) appears more than once, so solve the equation for it.")
                steps += polySteps.map { Step(named(f, it.title), named(f, it.math), it.note?.let { n -> named(f, n) }) }
                if (out is Outcome.Roots) {
                    for (root in out.roots.filter { it.isReal }) {
                        if (root.exact != null && rf.den.evaluate(root.exact).isZero) continue
                        found += root.exact?.let { S.Num(it) } ?: numberS(root.re)
                    }
                }
            } else {
                steps += Step("Solve numerically", named(f, "${Sym.latex(g)} = 0"),
                    "LocalMath can't rearrange this exactly, so it searches for the value that makes both sides equal.")
                found += numericRoots(g, u).map { numberS(it) }
                numeric = true
            }
            results = found
        }

        val real = results.filter { Sym.eval(it, emptyMap()).isFinite() }
        if (real.isEmpty()) throw MathError("There's no real value of ${target.label} that fits these numbers")
        val positive = real.filter { Sym.eval(it, emptyMap()) > 0 }
        val chosen = if (positive.isNotEmpty() && positive.size < real.size) positive else real
        if (chosen.size < real.size) notes += "Only the positive answer makes sense for ${target.meaning}."

        // Check by putting the answer back in.
        for (r in chosen) {
            val all = values + (u to r)
            val lv = Sym.eval(substitute(left, all), emptyMap())
            val rv = Sym.eval(substitute(right, all), emptyMap())
            if (!lv.isFinite() || !rv.isFinite() || Math.abs(lv - rv) > 1e-6 * Math.max(1.0, Math.abs(lv) + Math.abs(rv))) {
                throw MathError("Sorry, LocalMath couldn't verify the answer, so it won't show a possibly wrong one.")
            }
        }

        val shown = chosen.map { r ->
            val d = Sym.eval(r, emptyMap())
            val exact = Sym.latex(r)
            val eng = number(f, d, target.unit)
            Triple(r, exact, eng)
        }
        steps += Step(if (chosen.size == 1) "Result" else "Results",
            shown.joinToString(",\\quad ") { (r, exact, eng) ->
                val same = if (isFinance(f)) Eng.isExactPlain(r, target.unit)
                    else r is S.Num && (r as S.Num).v.isInteger && Math.abs(Sym.eval(r, emptyMap())) < 1000
                when {
                    numeric -> "${target.tex} \\approx $eng"
                    same -> "${target.tex} = $eng"
                    exact.length > 60 -> "${target.tex} \\approx $eng"      // a huge exact fraction helps no one
                    else -> "${target.tex} = $exact \\approx $eng"
                }
            }, notes.drop(if (iso != null) 1 else 0).firstOrNull())
        val answer = shown.joinToString(",\\quad ") { (r, _, eng) ->
            val exactNumber = if (isFinance(f)) Eng.isExactPlain(r, target.unit) else isTidy(r)
            "${target.tex} ${if (exactNumber) "=" else "\\approx"} $eng"
        }
        lastValues = chosen.map { Sym.eval(it, emptyMap()) }
        return Solution(f.name, steps, answer)
    }

    /** Numeric results of the most recent [solve] (used by tests). */
    @Volatile var lastValues: List<Double> = emptyList()
        private set

    /** True when the engineering form shows the value exactly (e.g. 2.5 mA, not 2.553 mA). */
    private fun isTidy(r: S): Boolean {
        val v = (r as? S.Num)?.v ?: return false
        return try {
            val bd = java.math.BigDecimal(v.num).divide(java.math.BigDecimal(v.den))
            bd.stripTrailingZeros().precision() <= 4
        } catch (_: ArithmeticException) { false }
    }

    private fun numberS(d: Double): S {
        val bd = java.math.BigDecimal(d).round(java.math.MathContext(12)).stripTrailingZeros()
        val r = Rational.parse(bd.abs().toPlainString())
        return S.Num(if (bd.signum() < 0) -r else r)
    }

    private fun substitute(s: S, values: Map<Char, S>): S {
        var out = s
        for ((c, v) in values) out = Sym.substitute(out, c, v)
        return out
    }

    /** Puts the numbers into the rearranged formula's LaTeX, in engineering notation. */
    /** Engineering notation (4.7 kΩ) for engineering formulas; plain numbers (12,345.68) for finance. */
    private fun number(f: Formula, d: Double, unit: String) = if (isFinance(f)) Eng.plainFormat(d, unit) else Eng.format(d, unit)

    private fun substituteTex(tex: String, values: Map<Char, S>, format: (Double) -> String): String {
        val sb = StringBuilder()
        for ((i, ch) in tex.withIndex()) {
            val v = values[ch]
            if (v == null) { sb.append(ch); continue }
            val shown = format(Sym.eval(v, emptyMap()))
            val before = tex.substring(0, i).trimEnd().lastOrNull()
            val after = tex.substring(i + 1).trimStart().firstOrNull()
            fun factorLike(c: Char?) = c != null && (c.isLetterOrDigit() || c in '\uE000'..'\uE0FF')
            val juxtaposed = factorLike(before) || before == '}' || before == ')' || factorLike(after) || after == '\\' || after == '^'
            val needs = shown.startsWith("-") || (juxtaposed && !(before == '{' && after == '}'))
            sb.append(if (needs) "\\left($shown\\right)" else shown)
        }
        return sb.toString()
    }

    private fun occurrences(s: S, u: Char): Int = when (s) {
        is S.Var -> if (s.name == u) 1 else 0
        is S.Sum -> s.terms.sumOf { occurrences(it, u) }
        is S.Prod -> s.factors.sumOf { occurrences(it, u) }
        is S.Pow -> occurrences(s.base, u) + occurrences(s.exp, u)
        is S.Func -> occurrences(s.arg, u)
        else -> 0
    }

    /** Undoes the operations around [u] one at a time: a(u) = b  ->  u = …  (null if it can't). */
    private fun isolate(a0: S, b0: S, u: Char, notes: MutableList<String>): S? {
        var a = a0
        var b = b0
        repeat(40) {
            if (a == S.Var(u)) return b
            val cur = a
            when (cur) {
                is S.Sum -> {
                    val (with, without) = cur.terms.partition { depends(it, u) }
                    if (with.size != 1) return null
                    b = add(b, neg(add(without)))
                    a = with[0]
                }
                is S.Prod -> {
                    val (with, without) = cur.factors.partition { depends(it, u) }
                    if (with.size != 1) return null
                    b = Sym.div(b, mul(without))
                    a = with[0]
                }
                is S.Pow -> when {
                    depends(cur.base, u) && !depends(cur.exp, u) -> {
                        val e = cur.exp
                        if (e is S.Num && e.v.num.mod(java.math.BigInteger.TWO).signum() == 0) notes += "Taking the positive root."
                        b = pow(b, pow(e, Sym.MINUS_ONE))
                        a = cur.base
                    }
                    !depends(cur.base, u) && depends(cur.exp, u) -> {
                        b = if (cur.base == Sym.E) Sym.func("ln", b) else Sym.div(Sym.func("ln", b), Sym.func("ln", cur.base))
                        a = cur.exp
                    }
                    else -> return null
                }
                is S.Func -> {
                    b = when (cur.name) {
                        "ln" -> pow(Sym.E, b)
                        "log" -> pow(Sym.num(10), b)
                        "sin" -> Sym.func("arcsin", b)
                        "cos" -> Sym.func("arccos", b)
                        "tan" -> Sym.func("arctan", b)
                        "arcsin" -> Sym.func("sin", b)
                        "arccos" -> Sym.func("cos", b)
                        "arctan" -> Sym.func("tan", b)
                        else -> return null
                    }
                    a = cur.arg
                }
                else -> return null
            }
        }
        return null
    }

    /** Real roots of g(u) = 0 by scanning and bisection (used when rearranging isn't possible). */
    private fun numericRoots(g: S, u: Char): List<Double> {
        fun at(x: Double) = Sym.eval(g, mapOf(u to x))
        val xs = mutableListOf<Double>()
        for (k in -15..15) for (m in listOf(1.0, 2.0, 5.0)) { val x = m * Math.pow(10.0, k.toDouble()); xs += x; xs += -x }
        xs += 0.0
        xs.sort()
        val roots = mutableListOf<Double>()
        for (i in 0 until xs.size - 1) {
            var lo = xs[i]; var hi = xs[i + 1]
            var flo = at(lo); val fhi = at(hi)
            if (!flo.isFinite() || !fhi.isFinite()) continue
            if (flo == 0.0) { roots += lo; continue }
            if (flo * fhi > 0) continue
            repeat(200) {
                val mid = (lo + hi) / 2
                val fm = at(mid)
                if (fm == 0.0 || !fm.isFinite()) { lo = mid; hi = mid; return@repeat }
                if (flo * fm < 0) hi = mid else { lo = mid; flo = fm }
            }
            val r = (lo + hi) / 2
            if (Math.abs(at(r)) < 1e-6 * Math.max(1.0, Math.abs(at(xs[i])))) roots += r
        }
        val distinct = roots.distinctBy { Math.round(it * 1e9) }
        // Crossings far out (|x| > 10¹²) are usually rounding noise where the formula levels off.
        val sensible = distinct.filter { Math.abs(it) <= 1e12 }
        return if (sensible.isNotEmpty()) sensible else distinct
    }
}
