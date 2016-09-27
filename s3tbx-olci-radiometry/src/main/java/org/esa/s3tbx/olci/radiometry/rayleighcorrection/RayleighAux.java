/*
 *
 *  * Copyright (C) 2012 Brockmann Consult GmbH (info@brockmann-consult.de)
 *  *
 *  * This program is free software; you can redistribute it and/or modify it
 *  * under the terms of the GNU General Public License as published by the Free
 *  * Software Foundation; either version 3 of the License, or (at your option)
 *  * any later version.
 *  * This program is distributed in the hope that it will be useful, but WITHOUT
 *  * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 *  * more details.
 *  *
 *  * You should have received a copy of the GNU General Public License along
 *  * with this program; if not, see http://www.gnu.org/licenses/
 *
 */

package org.esa.s3tbx.olci.radiometry.rayleighcorrection;

import org.apache.commons.math3.analysis.interpolation.BicubicSplineInterpolator;
import org.apache.commons.math3.analysis.interpolation.LinearInterpolator;
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction;
import org.esa.s3tbx.olci.radiometry.smilecorr.SmileUtils;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.dataop.dem.ElevationModel;
import org.esa.snap.core.dataop.dem.ElevationModelDescriptor;
import org.esa.snap.core.dataop.dem.ElevationModelRegistry;
import org.esa.snap.core.dataop.resamp.Resampling;
import org.esa.snap.core.gpf.Tile;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * @author muhammad.bc.
 */
public class RayleighAux {

    public static final String GETASSE_30 = "GETASSE30";
    public static final String COEFF_MATRIX_TXT = "coeffMatrix.txt";
    public static final String TAU_RAY = "tau_ray";
    public static final String THETA = "theta";
    public static final String RAY_COEFF_MATRIX = "ray_coeff_matrix";
    public static final String RAY_ALBEDO_LUT = "ray_albedo_lut";
    public static PolynomialSplineFunction linearInterpolate;
    public static double[] tau_ray;
    private static ElevationModel elevationModel;
    private static double[] thetas;
    private static double[][][] rayCooefMatrixA;
    private static double[][][] rayCooefMatrixB;
    private static double[][][] rayCooefMatrixC;
    private static double[][][] rayCooefMatrixD;
    private double[] sunZenithAngles;
    private double[] viewZenithAngles;
    private double[] sunAzimuthAngles;
    private double[] viewAzimuthAngles;
    private double[] seaLevels;
    private double[] totalOzones;
    private double[] latitudes;
    private double[] solarFluxs;
    private double[] lambdaSource;
    private double[] sourceSampleRad;
    private int sourceBandIndex;
    private float waveLength;
    private String sourceBandName;
    private double[] longitude;
    private double[] altitudes;
    private Map<Integer, double[]> fourierPoly;
    private Map<Integer, List<double[]>> interpolateMap;
    private double[] viewAzimuthAnglesRad;
    private double[] sunZenithAnglesRad;
    private double[] sunAzimuthAnglesRad;
    private double[] viewZenithAnglesRad;
    private double[] aziDiff;
    private double[] cosSZARads;
    private double[] sinOZARads;
    private double[] sinSZARads;
    private double[] cosOZARads;
    private double[] airMass;


    public static void initDefaultAuxiliary() {
        try {
            ElevationModelDescriptor getasse30 = ElevationModelRegistry.getInstance().getDescriptor(GETASSE_30);
            elevationModel = getasse30.createDem(Resampling.NEAREST_NEIGHBOUR);

            RayleighCorrectionAux rayleighCorrectionAux = new RayleighCorrectionAux();
            Path coeffMatrix = rayleighCorrectionAux.installAuxdata().resolve(COEFF_MATRIX_TXT);

            JSONParser jsonObject = new JSONParser();
            JSONObject parse = (JSONObject) jsonObject.parse(new FileReader(coeffMatrix.toString()));

            tau_ray = rayleighCorrectionAux.parseJSON1DimArray(parse, TAU_RAY);
            thetas = rayleighCorrectionAux.parseJSON1DimArray(parse, THETA);

            ArrayList<double[][][]> ray_coeff_matrix = rayleighCorrectionAux.parseJSON3DimArray(parse, RAY_COEFF_MATRIX);
            rayCooefMatrixA = ray_coeff_matrix.get(0);
            rayCooefMatrixB = ray_coeff_matrix.get(1);
            rayCooefMatrixC = ray_coeff_matrix.get(2);
            rayCooefMatrixD = ray_coeff_matrix.get(3);

            double[] lineSpace = getLineSpace(0, 1, 17);
            double[] rayAlbedoLuts = rayleighCorrectionAux.parseJSON1DimArray(parse, RAY_ALBEDO_LUT);
            linearInterpolate = new LinearInterpolator().interpolate(lineSpace, rayAlbedoLuts);

        } catch (IOException | ParseException e) {
            e.printStackTrace();
        }
    }

    public void setSolarFluxs(double[] solarFluxs) {
        this.solarFluxs = solarFluxs;
    }

    public void setLambdaSource(double[] lambdaSource) {
        this.lambdaSource = lambdaSource;
    }

    public void setAltitudes(Tile altitude) {
        this.altitudes = getSampleDoubles(altitude);
    }

    public void setAltitudes(double... alt) {
        this.altitudes = alt;
    }

    //for test only
    public void setInterpolation(HashMap<Integer, List<double[]>> integerHashMap) {
        this.interpolateMap = integerHashMap;
    }

    public double[] getTaur() {
        return tau_ray;
    }

    public double[] getAltitudes() {
        if (Objects.isNull(altitudes)) {
            double[] longitudes = getLongitude();
            double[] latitudes = getLatitudes();

            if (Objects.nonNull(longitudes) && Objects.nonNull(latitudes)) {
                double[] elevation = new double[latitudes.length];
                for (int i = 0; i < longitudes.length; i++) {
                    try {
                        elevation[i] = elevationModel.getElevation(new GeoPos(latitudes[i], longitudes[i]));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                altitudes = elevation;
            }
        }
        return altitudes;
    }


    void setInterpolation() {
        BicubicSplineInterpolator gridInterpolator = new BicubicSplineInterpolator();
        Map<Integer, List<double[]>> interpolate = new HashMap<>();
        double[] sunZenithAngles = getSunZenithAngles();
        double[] viewZenithAngles = getViewZenithAngles();

        //todo mba ask Mp if to use this approach.
        assert sunZenithAngles != null;

        if (Objects.nonNull(sunZenithAngles) && Objects.nonNull(viewZenithAngles)) {
            for (int index = 0; index < sunZenithAngles.length; index++) {
                double yVal = viewZenithAngles[index];
                double xVal = sunZenithAngles[index];

                List<double[]> valueList = new ArrayList<>();
                for (int i = 0; i < rayCooefMatrixA.length; i++) {
                    double thetaMin = thetas[0];
                    double thetaMax = thetas[thetas.length - 1];

                    if (yVal > thetaMin && yVal < thetaMax) {
                        double[] values = new double[4];
                        values[0] = gridInterpolator.interpolate(thetas, thetas, rayCooefMatrixA[i]).value(xVal, yVal);
                        values[1] = gridInterpolator.interpolate(thetas, thetas, rayCooefMatrixB[i]).value(xVal, yVal);
                        values[2] = gridInterpolator.interpolate(thetas, thetas, rayCooefMatrixC[i]).value(xVal, yVal);
                        values[3] = gridInterpolator.interpolate(thetas, thetas, rayCooefMatrixD[i]).value(xVal, yVal);
                        valueList.add(values);
                    } else {
                        valueList.add(new double[]{0, 0, 0, 0});
                    }
                }
                interpolate.put(index, valueList);
            }
            interpolateMap = interpolate;
        }
    }

    public Map<Integer, List<double[]>> getInterpolation() {
        if (Objects.isNull(interpolateMap)) {
            interpolateMap = getSpikeInterpolation();
        }
        return interpolateMap;
    }

    public Map<Integer, List<double[]>> getSpikeInterpolation() {
        double[] sunZenithAngles = getSunZenithAngles();
        double[] viewZenithAngles = getViewZenithAngles();
        Map<Integer, List<double[]>> interpolate = new HashMap<>();

        if (Objects.nonNull(sunZenithAngles) && Objects.nonNull(viewZenithAngles)) {
            for (int index = 0; index < sunZenithAngles.length; index++) {
                double yVal = viewZenithAngles[index];
                double xVal = sunZenithAngles[index];

                List<double[]> valueList = new ArrayList<>();
                for (int i = 0; i < rayCooefMatrixA.length; i++) {
                    double thetaMin = thetas[0];
                    double thetaMax = thetas[thetas.length - 1];
                    if (yVal > thetaMin && yVal < thetaMax) {
                        double[] values = new double[4];
                        values[0] = SpikeInterpolation.interpolate2D(rayCooefMatrixA[i], thetas, thetas, xVal, yVal);
                        values[1] = SpikeInterpolation.interpolate2D(rayCooefMatrixB[i], thetas, thetas, xVal, yVal);
                        values[2] = SpikeInterpolation.interpolate2D(rayCooefMatrixC[i], thetas, thetas, xVal, yVal);
                        values[3] = SpikeInterpolation.interpolate2D(rayCooefMatrixD[i], thetas, thetas, xVal, yVal);
                        valueList.add(values);
                    } else {
                        valueList.add(new double[]{0, 0, 0, 0});
                    }
                }
                interpolate.put(index, valueList);
            }
        }
        return interpolate;
    }

    private Map<Integer, double[]> getFourierMap() {
        // Fourier components of multiple scattering
        Map<Integer, double[]> fourierPoly = new HashMap<>();
        double[] sunZenithAnglesRad = getSunZenithAnglesRad();
        double[] viewZenithAnglesRad = getViewZenithAnglesRad();

        double[] cosSZARads = getCosSZARads();
        double[] cosOZARads = getCosOZARads();

        double[] sinSZARads = getSinSZARads();
        double[] sinOZARads = getSinOZARads();

        double[] sinOZA2s = getSquarePower(sinOZARads);
        double[] sinSZA2s = getSquarePower(sinSZARads);

        if (Objects.nonNull(sunZenithAnglesRad) && Objects.nonNull(viewZenithAnglesRad)) {
            for (int index = 0; index < sunZenithAnglesRad.length; index++) {
                double cosSZARad = cosSZARads[index];
                double cosOZARad = cosOZARads[index];

                double sinSZARad = sinSZARads[index];
                double sinOZARad = sinOZARads[index];

                double sinSZA2 = sinSZA2s[index];
                double sinOZA2 = sinOZA2s[index];

                double[] fourierSeries = new double[3];
                //Rayleigh Phase function, 3 Fourier terms
                fourierSeries[0] = (3.0 * 0.9587256 / 4.0 * (1 + (cosSZARad * cosSZARad) * (cosOZARad * cosOZARad) + (sinSZA2 * sinOZA2) / 2.0) + (1.0 - 0.9587256));
                fourierSeries[1] = (-3.0 * 0.9587256 / 4.0 * cosSZARad * cosOZARad * sinSZARad * sinOZARad);
                fourierSeries[2] = (3.0 * 0.9587256 / 16.0 * sinSZA2 * sinOZA2);

                fourierPoly.put(index, fourierSeries);
            }
            return fourierPoly;

        }
        throw new NullPointerException("The zenith angle or view angle is null. ");
    }

    public Map<Integer, double[]> getFourier() {
        if (Objects.isNull(fourierPoly)) {
            return fourierPoly = getFourierMap();
        }
        return fourierPoly;
    }

    public void setWavelength(float waveLenght) {
        this.waveLength = waveLenght;
    }

    public double getWaveLength() {
        return waveLength;
    }

    public void setSourceBandName(String targetBandName) {
        this.sourceBandName = targetBandName;
    }

    public String getSourceBandName() {
        return sourceBandName;
    }

    public void setSunAzimuthAnglesRad(double[] sunAzimuthAngles) {
        if (Objects.nonNull(sunAzimuthAngles)) {
            sunAzimuthAnglesRad = SmileUtils.convertDegreesToRadians(sunAzimuthAngles);
        }
    }

    public double[] getSunAzimuthAnglesRad() {
        if (Objects.nonNull(sunAzimuthAnglesRad)) {
            return sunAzimuthAnglesRad;
        }
        throw new NullPointerException("The sun azimuth angles is null.");
    }

    public void setViewAzimuthAnglesRad(double[] viewAzimuthAngles) {
        if (Objects.nonNull(viewAzimuthAngles)) {
            viewAzimuthAnglesRad = SmileUtils.convertDegreesToRadians(viewAzimuthAngles);
        }
    }

    public double[] getViewAzimuthAnglesRad() {
        if (Objects.nonNull(viewAzimuthAnglesRad)) {
            return viewAzimuthAnglesRad;
        }
        throw new NullPointerException("The view azimuth angles is null.");
    }

    public void setSunZenithAnglesRad(double[] sunZenithAngles) {
        if (Objects.nonNull(sunZenithAngles)) {
            sunZenithAnglesRad = SmileUtils.convertDegreesToRadians(sunZenithAngles);
        }
        setCosSZARads(sunZenithAnglesRad);
        setSinSZARads(sunZenithAnglesRad);
    }

    public double[] getSunZenithAnglesRad() {
        if (Objects.nonNull(sunZenithAnglesRad)) {
            return sunZenithAnglesRad;
        }
        throw new NullPointerException("The sun zenith angles is null.");
    }

    public void setViewZenithAnglesRad(double[] viewZenithAngles) {
        if (Objects.nonNull(viewZenithAngles)) {
            viewZenithAnglesRad = SmileUtils.convertDegreesToRadians(viewZenithAngles);
        }
        setCosOZARads(viewZenithAnglesRad);
        setSinOZARads(viewZenithAnglesRad);
    }

    public double[] getViewZenithAnglesRad() {
        if (Objects.nonNull(viewZenithAnglesRad)) {
            return viewZenithAnglesRad;
        }
        throw new NullPointerException("The view zenith angles is null.");
    }

    public double[] getAirMass() {
        if (Objects.isNull(airMass)) {
            airMass = SmileUtils.getAirMass(this.getCosOZARads(), this.getCosSZARads());
        }
        return airMass;
    }

    public double[] getAziDifferent() {
        if (Objects.isNull(aziDiff)) {
            aziDiff = SmileUtils.getAziDiff(this.getSunAzimuthAnglesRad(), this.getViewAzimuthAnglesRad());
        }
        return aziDiff;
    }

    public void setCosSZARads(double[] sunZenithAnglesRad) {
        if (Objects.nonNull(sunZenithAnglesRad)) {
            cosSZARads = Arrays.stream(sunZenithAnglesRad).map(Math::cos).toArray();
        }
    }

    public double[] getCosSZARads() {
        if (Objects.nonNull(cosSZARads)) {
            return cosSZARads;
        }
        throw new NullPointerException("The sun zenith angles is null.");
    }

    public void setCosOZARads(double[] zenithAnglesRad) {
        if (Objects.nonNull(zenithAnglesRad)) {
            cosOZARads = Arrays.stream(zenithAnglesRad).map(Math::cos).toArray();
        }
    }

    public double[] getCosOZARads() {
        if (Objects.nonNull(cosOZARads)) {
            return cosOZARads;
        }
        throw new NullPointerException("The view zenith angles is null.");
    }

    public void setSinSZARads(double[] sunZenithAnglesRad) {
        if (Objects.nonNull(sunZenithAnglesRad)) {
            sinSZARads = Arrays.stream(sunZenithAnglesRad).map(Math::sin).toArray();
        }
    }

    public double[] getSinSZARads() {
        if (Objects.nonNull(sinSZARads)) {
            return sinSZARads;
        }
        throw new NullPointerException("The sun zenith angles is null.");
    }

    public void setSinOZARads(double[] zenithAnglesRad) {
        if (Objects.nonNull(zenithAnglesRad)) {
            sinOZARads = Arrays.stream(zenithAnglesRad).map(Math::sin).toArray();
        }
    }

    public double[] getSinOZARads() {
        if (Objects.nonNull(sinOZARads)) {
            return sinOZARads;
        }
        throw new NullPointerException("The view zenith angles is null.");
    }

    public double[] getSunZenithAngles() {
        return sunZenithAngles;
    }

    public void setSunZenithAngles(double... sunZenithAngles) {
        this.sunZenithAngles = sunZenithAngles;
        setSunZenithAnglesRad(sunZenithAngles);
    }

    public void setSunZenithAngles(Tile sourceTile) {
        this.sunZenithAngles = getSampleDoubles(sourceTile);
        setSunZenithAnglesRad(sunZenithAngles);
    }

    public double[] getViewZenithAngles() {
        return viewZenithAngles;
    }

    public void setViewZenithAngles(Tile sourceTile) {
        this.viewZenithAngles = getSampleDoubles(sourceTile);
        setViewZenithAnglesRad(viewZenithAngles);
    }

    public void setViewZenithAngles(double... viewZenithAngles) {
        this.viewZenithAngles = viewZenithAngles;
        setViewZenithAnglesRad(viewZenithAngles);
    }

    public double[] getSunAzimuthAngles() {
        return sunAzimuthAngles;
    }

    public void setSunAzimuthAngles(Tile sourceTile) {
        this.sunAzimuthAngles = getSampleDoubles(sourceTile);
        setSunAzimuthAnglesRad(sunAzimuthAngles);
    }

    public void setSunAzimuthAngles(double... sunAzimuthAngles) {
        this.sunAzimuthAngles = sunAzimuthAngles;
        setSunAzimuthAnglesRad(sunAzimuthAngles);
    }

    public double[] getLatitudes() {
        return latitudes;
    }

    public void setLatitudes(Tile sourceTile) {
        this.latitudes = getSampleDoubles(sourceTile);
    }

    public void setLatitudes(double... lat) {
        this.latitudes = lat;
    }

    public double[] getViewAzimuthAngles() {
        return viewAzimuthAngles;
    }

    public void setViewAzimuthAngles(Tile sourceTile) {
        this.viewAzimuthAngles = getSampleDoubles(sourceTile);
        setViewAzimuthAnglesRad(viewAzimuthAngles);
    }

    public void setViewAzimuthAngles(double... viewAzimuthAngles) {
        this.viewAzimuthAngles = viewAzimuthAngles;
        setViewAzimuthAnglesRad(viewAzimuthAngles);
    }

    public double[] getSeaLevels() {
        return seaLevels;
    }

    public void setSeaLevels(Tile sourceTile) {
        this.seaLevels = getSampleDoubles(sourceTile);
    }

    public void setSeaLevels(double... seaLevels) {
        this.seaLevels = seaLevels;
    }

    public double[] getTotalOzones() {
        return totalOzones;
    }

    public void setTotalOzones(Tile sourceTile) {
        this.totalOzones = getSampleDoubles(sourceTile);
    }

    public void setTotalOzones(double... totalO) {
        this.totalOzones = totalO;
    }

    public double[] getSolarFluxs() {
        return solarFluxs;
    }

    public void setSolarFluxs(Tile sourceTile) {
        this.solarFluxs = getSampleDoubles(sourceTile);
    }

    public double[] getLambdaSource() {
        return lambdaSource;
    }

    public void setLambdaSource(Tile sourceTile) {
        this.lambdaSource = getSampleDoubles(sourceTile);
    }

    public double[] getSourceSampleRad() {
        return sourceSampleRad;
    }

    public void setSourceSampleRad(Tile sourceTile) {
        this.sourceSampleRad = getSampleDoubles(sourceTile);
    }

    public int getSourceBandIndex() {
        return sourceBandIndex;
    }

    public void setSourceBandIndex(int sourceBandIndex) {
        this.sourceBandIndex = sourceBandIndex;
    }

    public double[] getLongitude() {
        return longitude;
    }

    public void setLongitude(Tile sourceTile) {
        this.longitude = getSampleDoubles(sourceTile);
    }

    public void setLongitude(double... longitude) {
        this.longitude = longitude;
    }


    public double[] getInterpolateRayleighThickness(double... taur) {
        if (Objects.nonNull(taur)) {
            double[] val = new double[taur.length];
            for (int i = 0; i < taur.length; i++) {
                val[i] = linearInterpolate.value(taur[i]);
            }
            return val;
        }
        throw new NullPointerException("The linearInterpolate Rayleigh thickness is empty.");
    }


    static double[] getLineSpace(double start, double end, int interval) {
        if (interval < 0) {
            throw new NegativeArraySizeException("Array must not have negative index");
        }
        double[] temp = new double[interval];
        double steps = (end - start) / (interval - 1);
        for (int i = 0; i < temp.length; i++) {
            temp[i] = steps * i;
        }
        return temp;
    }

    //todo mb/*** write a test
    public double[] getSquarePower(double[] sinOZARads) {
        if (Objects.nonNull(sinOZARads)) {
            return Arrays.stream(sinOZARads).map(p -> Math.pow(p, 2)).toArray();
        }
        throw new NullPointerException("The array is null.");
    }

    public static double[] getSampleDoubles(Tile sourceTile) {
        int maxX = sourceTile.getWidth();
        int maxY = sourceTile.getHeight();

        double[] val = new double[maxX * maxY];
        int index = 0;
        for (int y = sourceTile.getMinY(); y <= sourceTile.getMaxY(); y++) {
            for (int x = sourceTile.getMinX(); x <= sourceTile.getMaxX(); x++) {
                val[index++] = sourceTile.getSampleDouble(x, y);
            }
        }
        return val;
    }
}